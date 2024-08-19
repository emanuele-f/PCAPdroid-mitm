/*
 * This file is part of PCAPdroid.
 *
 * PCAPdroid is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * PCAPdroid is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with PCAPdroid.  If not, see <http://www.gnu.org/licenses/>.
 *
 * Copyright 2023 - Emanuele Faranda
 */

package com.pcapdroid.mitm;

import android.Manifest;
import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.pm.PackageManager;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.preference.PreferenceManager;
import android.provider.DocumentsContract;
import android.provider.Settings;
import android.util.Log;
import android.view.Menu;
import android.view.MenuItem;
import android.view.View;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

public class AddonsActivity extends Activity implements AddonsAdapter.AddonListener {
    private static final String TAG = "UserAddons";
    private static final String USER_DIR_PREF = "user-dir";
    private static final String ENABLED_ADDONS_PREF = "enabled-addons";
    private static final int OPEN_DIR_TREE_CODE = 1;
    private static final int REQUEST_FILES_ACCESS_CODE = 2;

    private TextView mEmptyText;
    private SharedPreferences mPrefs;
    private AddonsAdapter mAdapter;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setTitle(R.string.addons);
        setContentView(R.layout.simple_list);

        mEmptyText = findViewById(R.id.list_empty);
        mPrefs = PreferenceManager.getDefaultSharedPreferences(this);

        mAdapter = new AddonsAdapter(this);
        mAdapter.setListener(this);
        ((ListView)findViewById(R.id.listview))
                .setAdapter(mAdapter);
        refreshAddons();
    }

    private void refreshAddons() {
        Set<String> enabledAddons = getEnabledAddons(this);
        List<Addon> addons = new ArrayList<>();

        // Internal addons
        addons.add(new Addon("Js Injector",
                "Inject javascript into web pages",
                enabledAddons.contains("Js Injector"),
                Addon.AddonType.JsInjector));

        // User addons
        Uri publicUri = getUserDir(this);
        String descr = getString(R.string.user_addon);

        if(publicUri != null) {
            for (Uri uri : listUserAddons(this, publicUri)) {
                String path = uri.getPath();
                int slash = path.lastIndexOf("/");
                if (slash > 0) {
                    String fname = path.substring(slash + 1);
                    if (fname.endsWith(".py")) {
                        String script = fname.substring(0, fname.length() - 3);
                        addons.add(new Addon(script, descr, enabledAddons.contains(script)));
                    }
                }
            }
        }

        mAdapter.reload(addons);
        recheckListSize();

        if(MitmService.isRunning())
            (Toast.makeText(this, R.string.restart_to_apply, Toast.LENGTH_SHORT)).show();
    }

    // get the file name of the enabled addons
    public static Set<String> getEnabledAddons(Context ctx) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(ctx);

        HashSet<String> s = new HashSet<>();
        Set<String> rv = prefs.getStringSet(ENABLED_ADDONS_PREF, s);
        if (!rv.isEmpty())
            // Use a copy as the set returned by getStringSet cannot be modified
            s.addAll(rv);

        return s;
    }

    private void recheckListSize() {
        mEmptyText.setVisibility((mAdapter.getCount() == 0) ? View.VISIBLE : View.GONE);
    }

    public static List<Uri> listUserAddons(Context ctx, Uri publicUri) {
        List<Uri> rv = new ArrayList<>();

        try {
            Uri srcFolder = DocumentsContract.buildChildDocumentsUriUsingTree(publicUri, DocumentsContract.getTreeDocumentId(publicUri));

            try (Cursor cursor = ctx.getContentResolver().query(srcFolder,
                    new String[]{ DocumentsContract.Document.COLUMN_DOCUMENT_ID },
                    null, null, null)) {
                if ((cursor != null) && cursor.moveToFirst()) {
                    do {
                        Uri uri = DocumentsContract.buildDocumentUriUsingTree(publicUri, cursor.getString(0));
                        rv.add(uri);
                    } while (cursor.moveToNext());
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
            Log.e(TAG, e.toString());
        }

        return rv;
    }

    /* Since we can only access the addons dir via the ContentResolver, we copy all the addons
     * to the app private dir to make python import work. */
    public static boolean copyAddonsToPrivDir(Context ctx, File privAddons) {
        Uri publicUri = getUserDir(ctx);
        if (publicUri == null)
            return false;

        privAddons.mkdirs();

        // delete all existing .py files, but not the other kind of files, possibly created by the addons
        for (File f : Objects.requireNonNull(privAddons.listFiles())) {
            String name = f.getName();
            if (name.endsWith(".py") || (name.equals("__pycache__")))
                f.delete();
        }

        Uri srcFolder = DocumentsContract.buildChildDocumentsUriUsingTree(publicUri, DocumentsContract.getTreeDocumentId(publicUri));
        Log.d(TAG, "Copying public addons from: " + srcFolder + " to " + privAddons);
        List<Uri> addonsUris = listUserAddons(ctx, publicUri);

        for(Uri srcUri: addonsUris) {
            String path = srcUri.getPath();
            if (path == null)
                continue;

            int slash = path.lastIndexOf("/");
            if (slash > 0) {
                String srcFname = path.substring(slash + 1);

                if (!srcFname.endsWith(".py"))
                    continue;

                File outFile = new File(privAddons.getAbsolutePath() + "/" + srcFname);
                Log.d(TAG, "Found addon: " + srcFname);

                // Copy from srcUri to outFile
                try {
                    try (InputStream in = new BufferedInputStream(ctx.getContentResolver().openInputStream(srcUri))) {
                        try (OutputStream out = new BufferedOutputStream(new FileOutputStream(outFile))) {
                            byte[] bytesIn = new byte[4096];
                            int read;
                            while ((read = in.read(bytesIn)) != -1)
                                out.write(bytesIn, 0, read);
                        }
                    }
                } catch (IOException e) {
                    e.printStackTrace();
                    Log.e(TAG, e.toString());

                    return false;
                }
            }
        }

        return true;
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        // Inflate the menu; this adds items to the action bar if it is present.
        getMenuInflater().inflate(R.menu.addons, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();

        if(id == R.id.show_hint) {
            Utils.showHintDialog(this, R.string.addons_hint);
            return true;
        } else if(id == R.id.update) {
            refreshAddons();
            return true;
        } else if(id == R.id.select_user_dir) {
            selectUserDir();
            return true;
        } else if(id == R.id.enable_files_access) {
            enableFilesAccess();
            return true;
        }

        return super.onOptionsItemSelected(item);
    }

    private void selectUserDir() {
        Intent intent = new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE);

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            // Initial path
            intent.putExtra(DocumentsContract.EXTRA_INITIAL_URI, Uri.parse(Environment.getExternalStorageDirectory().getAbsolutePath()));
        }

        (Toast.makeText(this, R.string.specify_user_dir, Toast.LENGTH_LONG)).show();
        startActivityForResult(intent, OPEN_DIR_TREE_CODE);
    }

    @Override
    protected void onActivityResult(int requestCode, int resultCode, Intent resultData) {
        if((requestCode == OPEN_DIR_TREE_CODE) && (resultCode == RESULT_OK) && (resultData != null)) {
            Uri user_dir = resultData.getData();

            if (user_dir != null) {
                // Persist access across restarts
                getContentResolver().takePersistableUriPermission(user_dir, Intent.FLAG_GRANT_READ_URI_PERMISSION);

                mPrefs.edit()
                        .putString(USER_DIR_PREF, user_dir.toString())
                        .apply();

                refreshAddons();
            }
        }
    }

    public static Uri getUserDir(Context ctx) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(ctx);
        Uri publicUri = Uri.parse(prefs.getString(AddonsActivity.USER_DIR_PREF, ""));

        if ((publicUri.getHost() == null) || !Utils.hasPersistableReadPermission(ctx, publicUri))
            return null;

        // use buildDocumentUriUsingTree so that it can be used with Utils.uriToFilePath
        return DocumentsContract.buildDocumentUriUsingTree(publicUri,
                DocumentsContract.getTreeDocumentId(publicUri));
    }

    public static boolean hasFilesAccess(Context ctx) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            return Environment.isExternalStorageManager();
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            return ctx.checkSelfPermission(Manifest.permission.WRITE_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED &&
                    ctx.checkSelfPermission(Manifest.permission.READ_EXTERNAL_STORAGE) == PackageManager.PERMISSION_GRANTED;
        } else
            // on older Android versions, it's automatically granted
            return true;
    }

    private void enableFilesAccess() {
        new AlertDialog.Builder(this)
                .setTitle(R.string.enable_files_access)
                .setMessage(R.string.enable_files_access_info)
                .setPositiveButton(R.string.enable, (dialogInterface, i) -> {
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                        Intent intent = new Intent();
                        intent.setAction(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION);
                        intent.setData(Uri.fromParts("package", this.getPackageName(), null));
                        startActivityForResult(intent, REQUEST_FILES_ACCESS_CODE);
                    } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
                        if (hasFilesAccess(this)) {
                            Toast.makeText(this, R.string.permission_already_granted, Toast.LENGTH_SHORT).show();
                            return;
                        }

                        requestPermissions(
                                new String[] {
                                        Manifest.permission.WRITE_EXTERNAL_STORAGE,
                                        Manifest.permission.READ_EXTERNAL_STORAGE
                                },
                                REQUEST_FILES_ACCESS_CODE
                        );
                    }
                })
                .setNeutralButton(android.R.string.cancel, (dialogInterface, i) -> {})
                .show();
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        if (requestCode == REQUEST_FILES_ACCESS_CODE) {
            boolean granted = (grantResults.length > 0) && (grantResults[0] == PackageManager.PERMISSION_GRANTED);
            Log.d(TAG, "Request files access: granted=" + granted);

            if (!granted)
                (Toast.makeText(this, R.string.files_access_not_granted, Toast.LENGTH_LONG)).show();
        }
    }

    @Override
    public void onAddonToggled(Addon addon, boolean enabled) {
        Set<String> enabledAddons = getEnabledAddons(AddonsActivity.this);
        addon.enabled = enabled;

        if(enabled)
            enabledAddons.add(addon.fname);
        else
            enabledAddons.remove(addon.fname);

        mPrefs.edit()
                .putStringSet(ENABLED_ADDONS_PREF, enabledAddons)
                .apply();

        refreshAddons();
    }

    @Override
    public void onAddonSettingsClicked(Addon addon) {
        if(addon.type == Addon.AddonType.JsInjector) {
            Intent intent = new Intent(this, JsInjectorActivity.class);
            startActivity(intent);
        }
    }
}
