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
 * Copyright 2022 - Emanuele Faranda
 */

package com.pcapdroid.mitm;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;

import com.chaquo.python.PyObject;
import com.chaquo.python.Python;

// TODO don't use activity, as it shows a window
public class MitmCtrl extends Activity {
    static final String TAG = "MitmCtrl";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);

        Intent intent = getIntent();
        if(intent == null) {
            Log.d(TAG, "null intent");
            abort();
            return;
        }

        String action = intent.getStringExtra(MitmAddon.ACTION_EXTRA);
        if(action == null) {
            Log.d(TAG, "missing action");
            abort();
            return;
        }

        if(action.equals(MitmAddon.ACTION_GET_CA_CERTIFICATE)) {
            Python py = Python.getInstance();
            PyObject mitm = py.getModule("mitm");

            PyObject pyres = mitm.callAttr("getCAcert");
            if(pyres != null) {
                String cert = pyres.toJava(String.class);
                Intent res = new Intent();
                res.putExtra(MitmAddon.CERTIFICATE_RESULT, cert);

                setResult(RESULT_OK, res);
                finish();
                return;
            }
        } else {
            Log.d(TAG, "Bad action: " + action);
        }

        abort();
    }

    private void abort() {
        setResult(RESULT_CANCELED, null);
        finish();
    }
}
