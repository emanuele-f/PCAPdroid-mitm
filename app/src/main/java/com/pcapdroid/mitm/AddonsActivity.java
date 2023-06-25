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

import android.app.Activity;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.content.UriPermission;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.preference.PreferenceManager;
import android.provider.DocumentsContract;
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
import java.util.Set;

public class AddonsActivity extends Activity implements AddonsAdapter.AddonListener {
    private static final String TAG = "UserAddons";
    private static final String USER_DIR_PREF = "user-dir";
    private static final String ENABLED_ADDONS_PREF = "enabled-addons";
    private static final int OPEN_DIR_TREE_CODE = 1;

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
        Uri publicUri = Uri.parse(mPrefs.getString(USER_DIR_PREF, ""));
        String descr = getString(R.string.user_addon);

        if((publicUri.getHost() != null) && hasUriPersistablePermission(this, publicUri)) {
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
        return prefs.getStringSet(ENABLED_ADDONS_PREF, new HashSet<>());
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
    public static boolean copyAddonsToPrivDir(Context ctx, String privDir) {
        SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(ctx);
        Uri publicUri = Uri.parse(prefs.getString(USER_DIR_PREF, ""));

        if((publicUri.getHost() == null) || !hasUriPersistablePermission(ctx, publicUri))
            return false;

        File privAddons = new File(privDir);
        privAddons.delete();
        privAddons.mkdirs();

        Uri srcFolder = DocumentsContract.buildChildDocumentsUriUsingTree(publicUri, DocumentsContract.getTreeDocumentId(publicUri));
        Log.d(TAG, "Addons source dir: " + srcFolder);
        List<Uri> addonsUris = listUserAddons(ctx, publicUri);

        for(Uri srcUri: addonsUris) {
            String path = srcUri.getPath();
            int slash = path.lastIndexOf("/");
            if (slash > 0) {
                String srcFname = path.substring(slash + 1);
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

    private static boolean hasUriPersistablePermission(Context ctx, Uri uri) {
        List<UriPermission> persistableUris = ctx.getContentResolver().getPersistedUriPermissions();

        for(UriPermission perm: persistableUris) {
            if(perm.getUri().equals(uri) && perm.isReadPermission())
                return true;
        }

        return false;
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

            // Persist access across restarts
            getContentResolver().takePersistableUriPermission(user_dir, Intent.FLAG_GRANT_READ_URI_PERMISSION);

            mPrefs.edit()
                    .putString(USER_DIR_PREF, user_dir.toString())
                    .apply();

            refreshAddons();
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
