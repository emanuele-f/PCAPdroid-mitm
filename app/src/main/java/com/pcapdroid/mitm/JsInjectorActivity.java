package com.pcapdroid.mitm;

import android.app.Activity;
import android.app.AlertDialog;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.preference.PreferenceManager;
import android.text.TextUtils;
import android.text.method.LinkMovementMethod;
import android.util.Log;
import android.view.ActionMode;
import android.view.LayoutInflater;
import android.view.Menu;
import android.view.MenuInflater;
import android.view.MenuItem;
import android.view.View;
import android.widget.AbsListView;
import android.widget.EditText;
import android.widget.ListView;
import android.widget.TextView;
import android.widget.Toast;

import com.chaquo.python.PyObject;
import com.chaquo.python.Python;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.regex.Pattern;

public class JsInjectorActivity extends Activity {
    private static final String TAG = "JsInjectorActivity";
    private static final String SCRIPTS_PREF = "js-scripts";
    private static final String SEPARATOR = "|";

    private SharedPreferences mPrefs;
    private ArrayList<String> mUrls = new ArrayList<>();
    private ListView mListView;
    private TextView mEmptyText;
    private ScriptsAdapter mAdapter;
    private final ExecutorService mDownloadWorkers = Executors.newFixedThreadPool(4);
    private final HashSet<String> mUrlsDownloading = new HashSet<>();
    private final Handler mHandler = new Handler(Looper.getMainLooper());
    private ActionMode mActionMode;
    private final ArrayList<IJsUserscript> mSelected = new ArrayList<>();
    PyObject userscripts;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setTitle(R.string.js_injector);
        setContentView(R.layout.simple_list);

        mListView = findViewById(R.id.listview);
        mEmptyText = findViewById(R.id.list_empty);

        mPrefs = PreferenceManager.getDefaultSharedPreferences(this);
        String prefVal = mPrefs.getString(SCRIPTS_PREF, "");
        if(!prefVal.isEmpty())
            mUrls = new ArrayList<>(Arrays.asList(
                mPrefs.getString(SCRIPTS_PREF, "").split(Pattern.quote(SEPARATOR))));

        mAdapter = new ScriptsAdapter(this);
        mListView.setAdapter(mAdapter);
        mListView.setChoiceMode(ListView.CHOICE_MODE_MULTIPLE_MODAL);
        mListView.setOnItemClickListener((parent, view, position, id) -> {
            IJsUserscript script = mAdapter.getItem(position);
            if(script != null) {
                String url = getScriptUrl(script.getFname());
                if(url != null) {
                    Intent browserIntent = new Intent(Intent.ACTION_VIEW, Uri.parse(url));
                    startActivity(browserIntent);
                }
            }
        });
        mListView.setMultiChoiceModeListener(new AbsListView.MultiChoiceModeListener() {
            @Override
            public boolean onCreateActionMode(ActionMode mode, Menu menu) {
                MenuInflater inflater = getMenuInflater();
                inflater.inflate(R.menu.script_cab, menu);
                mActionMode = mode;
                return true;
            }

            @Override
            public boolean onPrepareActionMode(ActionMode mode, Menu menu) {
                return false;
            }

            @Override
            public boolean onActionItemClicked(ActionMode mode, MenuItem item) {
                int id = item.getItemId();

                if(id == R.id.delete) {
                    confirmDelete(mode);
                    return true;
                } else if(id == R.id.select_all) {
                    if(mSelected.size() >= mAdapter.getCount())
                        mode.finish();
                    else {
                        for(int i=0; i<mAdapter.getCount(); i++) {
                            if(!mListView.isItemChecked(i))
                                mListView.setItemChecked(i, true);
                        }
                    }
                    return true;
                } else if(id == R.id.update) {
                    boolean already_in_progress = false;
                    boolean reload = false;

                    for(IJsUserscript script : mSelected) {
                        String url = getScriptUrl(script.getFname());

                        if(url != null) {
                            if(mUrlsDownloading.contains(url))
                                already_in_progress = true;
                            else {
                                downloadScript(url);
                                reload = true;
                            }
                        }
                    }

                    if(already_in_progress)
                        Toast.makeText(JsInjectorActivity.this, R.string.download_in_progress, Toast.LENGTH_SHORT)
                                .show();

                    if(reload)
                        refreshScripts();

                    return true;
                }

                return false;
            }

            @Override
            public void onDestroyActionMode(ActionMode mode) {
                mSelected.clear();
                mActionMode = null;
            }

            @Override
            public void onItemCheckedStateChanged(ActionMode mode, int position, long id, boolean checked) {
                IJsUserscript item = mAdapter.getItem(position);

                if(checked)
                    mSelected.add(item);
                else
                    mSelected.remove(item);

                mode.setTitle(getString(R.string.n_selected, mSelected.size()));
            }
        });

        Python py = Python.getInstance();
        userscripts = py.getModule("userscripts");
        refreshScripts();
    }

    private void confirmDelete(ActionMode mode) {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setMessage(R.string.items_delete_confirm);
        builder.setCancelable(true);
        builder.setPositiveButton(android.R.string.yes, (dialog, which) -> {
            if(mSelected.size() >= mAdapter.getCount()) {
                mAdapter.clear();
                mUrls.clear();
                saveUrls();
            } else {
                for(IJsUserscript item : mSelected)
                    mAdapter.remove(item);
                updateListFromAdapter();
            }

            mode.finish();
            mActionMode = null;
            refreshScripts();

            MitmService.reloadJsInjectorScripts();
            recheckListSize();
        });
        builder.setNegativeButton(android.R.string.no, (dialog, whichButton) -> {});

        final AlertDialog alert = builder.create();
        alert.setCanceledOnTouchOutside(true);
        alert.show();
    }

    private void updateListFromAdapter() {
        HashSet<String> toKeep = new HashSet<>();

        // Remove the URLs which are not in the adapter dataset
        for(int i=0; i<mAdapter.getCount(); i++)
            toKeep.add(mAdapter.getItem(i).getFname());

        Iterator<String> it = mUrls.iterator();
        while(it.hasNext()) {
            String url = it.next();
            String fname = urlFileName(url);

            if(!toKeep.contains(fname)) {
                it.remove();
                mUrlsDownloading.remove(url);
            }
        }

        saveUrls();
    }

    @Override
    protected void onDestroy() {
        mDownloadWorkers.shutdownNow();
        mUrlsDownloading.clear();

        super.onDestroy();
    }

    @Override
    public boolean onCreateOptionsMenu(Menu menu) {
        // Inflate the menu; this adds items to the action bar if it is present.
        getMenuInflater().inflate(R.menu.js_injector, menu);
        return true;
    }

    @Override
    public boolean onOptionsItemSelected(MenuItem item) {
        int id = item.getItemId();

        if(id == R.id.show_hint) {
            showHintDialog(R.string.js_injector_hint);
            return true;
        } else if(id == R.id.add) {
            showAddScriptDialog();
            return true;
        } else if(id == R.id.update) {
            for (String url: mUrls) {
                if(!mUrlsDownloading.contains(url))
                    downloadScript(url);
            }

            refreshScripts();
        }

        return super.onOptionsItemSelected(item);
    }

    private void showHintDialog(int id) {
        AlertDialog.Builder builder = new AlertDialog.Builder(this);
        builder.setTitle(R.string.hint);
        builder.setMessage(getString(id));
        builder.setCancelable(true);
        builder.setNeutralButton(android.R.string.ok,
                (dialog, id1) -> dialog.cancel());

        AlertDialog alert = builder.create();
        alert.show();

        TextView message = (TextView)alert.findViewById(android.R.id.message);
        if(message != null) {
            message.setMovementMethod(LinkMovementMethod.getInstance());
            message.setText(id);
        }
    }

    private void showAddScriptDialog() {
        LayoutInflater inflater = LayoutInflater.from(this);
        View view = inflater.inflate(R.layout.add_script_dialog, null);

        final EditText url_edit = view.findViewById(R.id.url);

        AlertDialog dialog = new AlertDialog.Builder(this)
                .setView(view)
                .setTitle(R.string.add_script)
                .setPositiveButton(R.string.add_action, (dialogInterface, i) -> {})
                .setNegativeButton(R.string.cancel_action, (dialogInterface, i) -> {})
                .show();
        dialog.setCanceledOnTouchOutside(false);

        dialog.getButton(AlertDialog.BUTTON_POSITIVE)
                .setOnClickListener(v -> {
                    String url = url_edit.getText().toString();
                    if(urlFileName(url).isEmpty()) {
                        Toast.makeText(this, R.string.missing_required_parameter, Toast.LENGTH_SHORT)
                                .show();
                        return;
                    }

                    addScript(url);
                    dialog.dismiss();
                });
    }

    private String urlFileName(String url) {
        int idx = url.lastIndexOf('/');
        if(idx == -1)
            return "";
        return url.substring(idx + 1);
    }

    private void saveUrls() {
        mPrefs.edit()
                .putString(SCRIPTS_PREF, String.join(SEPARATOR, mUrls))
                .apply();
    }

    private boolean addScript(String url) {
        String fname = urlFileName(url);
        if(fname.isEmpty())
            return false;

        if(hasScript(fname)) {
            Toast.makeText(this, R.string.script_already_defined, Toast.LENGTH_LONG)
                    .show();
            return false;
        }

        mUrls.add(url);
        saveUrls();
        refreshScripts();
        return true;
    }

    private ArrayList<IJsUserscript> getScriptsOnDisk() {
        ArrayList<IJsUserscript> rv = new ArrayList<>();

        PyObject pyres = userscripts.callAttr("getJsUserscripts");
        if(pyres != null) {
            List<PyObject> scripts_obj = pyres.asList();

            for(PyObject script_obj: scripts_obj) {
                IJsUserscript script = script_obj.toJava(IJsUserscript.class);
                Log.d(TAG, "Js userscript: " + script.getName());
                rv.add(script);
            }
        }

        return rv;
    }

    private String getScriptUrl(String fname) {
        for(String u: mUrls) {
            if(urlFileName(u).equals(fname))
                return u;
        }

        return null;
    }

    private boolean hasScript(String fname) {
        return getScriptUrl(fname) != null;
    }

    private static class LoadInProgressScript implements IJsUserscript {
        private final String mFname;
        private final String mLoadInProgress;

        public LoadInProgressScript(String fname, String load_in_progress) {
            mFname = fname;
            mLoadInProgress = load_in_progress;
        }

        @Override
        public String getName() {
            return mFname;
        }

        @Override
        public String getAuthor() {
            return "";
        }

        @Override
        public String getVersion() {
            return "";
        }

        @Override
        public String getDescription() {
            return mLoadInProgress;
        }

        @Override
        public String getFname() {
            return mFname;
        }
    }

    private void recheckListSize() {
        mEmptyText.setVisibility((mAdapter.getCount() == 0) ? View.VISIBLE : View.GONE);
    }

    private void refreshScripts() {
        if(mActionMode != null) {
            mActionMode.finish();
            mActionMode = null;
        }

        ArrayList<IJsUserscript> on_disk = getScriptsOnDisk();
        HashMap<String, IJsUserscript> fname_to_script = new HashMap<>();

        // Delete any unknown script
        for(IJsUserscript on_disk_script: on_disk) {
            String fname = on_disk_script.getFname();

            if(!hasScript(fname)) {
                PyObject pyres = userscripts.callAttr("getScriptPath", fname);
                if(pyres != null) {
                    Log.i(TAG, "Deleting unknown script " + fname);
                    String path = pyres.toString();

                    //noinspection ResultOfMethodCallIgnored
                    (new File(path)).delete();
                }
            } else
                fname_to_script.put(fname, on_disk_script);
        }

        ArrayList<IJsUserscript> model = new ArrayList<>();

        // Honor the order of mUrls
        for(String url: mUrls) {
            String fname = urlFileName(url);
            IJsUserscript script = fname_to_script.get(fname);

            if((script == null) || mUrlsDownloading.contains(url)) {
                script = new LoadInProgressScript(fname, getString(R.string.loading));
                downloadScript(url);
            }

            model.add(script);
        }

        mAdapter.reload(model);
        recheckListSize();
    }

    private void downloadScript(String url) {
        if(mUrlsDownloading.contains(url))
            return;

        String fname = urlFileName(url);
        PyObject pyres = userscripts.callAttr("getScriptPath", fname);
        assert(pyres != null);
        final String outputPath = pyres.toString();

        try {
            mDownloadWorkers.execute(() -> {
                Log.i(TAG, "Downloading " + url);

                boolean success = downloadFile(url, outputPath);
                mHandler.post(() -> onScriptDownloadFinished(url, success));
            });

            mUrlsDownloading.add(url);
        } catch (RejectedExecutionException e) {
            Log.e(TAG, e.toString());
        }
    }

    private void onScriptDownloadFinished(String url, boolean success) {
        Log.d(TAG, "Script " + url + " download success? " + success);

        boolean found = mUrlsDownloading.remove(url);
        if(!found)
            return;

        if(success) {
            MitmService.reloadJsInjectorScripts();
            refreshScripts();
        } else
            Toast.makeText(this, getString(R.string.script_download_failed, urlFileName(url)), Toast.LENGTH_LONG)
                    .show();
    }

    private static boolean downloadFile(String _url, String path) {
        boolean has_contents = false;

        try (FileOutputStream out = new FileOutputStream(path)) {
            URL url = new URL(_url);

            try (BufferedOutputStream bos = new BufferedOutputStream(out)) {
                HttpURLConnection con = (HttpURLConnection) url.openConnection();
                try {
                    // Necessary otherwise the connection will stay open
                    con.setRequestProperty("Connection", "Close");
                    con.setConnectTimeout(5000);
                    con.setReadTimeout(5000);

                    try (InputStream in = new BufferedInputStream(con.getInputStream())) {
                        byte[] bytesIn = new byte[4096];
                        int read;
                        while ((read = in.read(bytesIn)) != -1) {
                            bos.write(bytesIn, 0, read);
                            has_contents |= (read > 0);
                        }
                    } catch (SocketTimeoutException _ignored) {
                        Log.w(TAG, "Timeout while fetching " + _url);
                    }
                } finally {
                    con.disconnect();
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        }

        if (!has_contents) {
            Log.d(TAG, "Downloaded file from " + _url + " is empty");

            try {
                //noinspection ResultOfMethodCallIgnored
                (new File(path + ".tmp")).delete(); // if exists
            } catch (Exception ignored) {
                // ignore
            }
            return false;
        }

        // Only write the target path if it was successful
        File dst = new File(path);

        // NOTE: renameTo seem to return false even on success
        //noinspection ResultOfMethodCallIgnored
        (new File(path + ".tmp")).renameTo(dst);

        return dst.exists();
    }
}
