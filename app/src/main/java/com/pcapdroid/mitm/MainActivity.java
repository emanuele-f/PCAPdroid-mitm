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
import android.content.ActivityNotFoundException;
import android.content.Intent;
import android.os.Bundle;
import android.widget.TextView;
import android.widget.Toast;

import com.chaquo.python.PyObject;
import com.chaquo.python.Python;

public class MainActivity extends Activity {
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.main_activity);

        Python py = Python.getInstance();
        PyObject metadata = py.getModule("importlib.metadata");
        PyObject sys = py.getModule("sys");
        PyObject openssl_backend_module = py.getModule("cryptography.hazmat.backends.openssl.backend");
        PyObject openssl_backend = openssl_backend_module.get("backend");

        ((TextView)findViewById(R.id.addon_version)).setText("v" + BuildConfig.VERSION_NAME);
        ((TextView)findViewById(R.id.abi)).setText(BuildConfig.FLAVOR);
        ((TextView)findViewById(R.id.mitmproxy_version)).setText(
                metadata.callAttr("version", "mitmproxy").toString());
        ((TextView)findViewById(R.id.cryptography_version)).setText(
                metadata.callAttr("version", "cryptography").toString());
        ((TextView)findViewById(R.id.openssl_version)).setText(
                openssl_backend.callAttr("openssl_version_text").toString());
        ((TextView)findViewById(R.id.python_version)).setText(
                sys.get("version").toString());
        findViewById(R.id.open_pcapdroid).setOnClickListener(v -> {
            try {
                Intent intent = null;
                try {
                    intent = getPackageManager().getLaunchIntentForPackage("com.emanuelef.remote_capture");
                } catch (ActivityNotFoundException ignored) {}

                if(intent == null)
                    intent = getPackageManager().getLaunchIntentForPackage("com.emanuelef.remote_capture.debug");

                if(intent != null) {
                    startActivity(intent);
                    return;
                }
            } catch (ActivityNotFoundException | SecurityException ignored) {}

            (Toast.makeText(this, R.string.app_not_found, Toast.LENGTH_SHORT)).show();
        });
        findViewById(R.id.addons).setOnClickListener(v -> {
            Intent intent = new Intent(MainActivity.this, AddonsActivity.class);
            startActivity(intent);
        });
    }
}