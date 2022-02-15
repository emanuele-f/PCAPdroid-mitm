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

import android.app.Service;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.os.Messenger;
import android.os.Parcel;
import android.os.ParcelFileDescriptor;
import android.os.RemoteException;
import android.util.Log;

import com.chaquo.python.PyObject;
import com.chaquo.python.Python;

public class MitmService extends Service implements Runnable {
    static final String TAG = "Mitmproxy";
    Messenger mMessenger;
    ParcelFileDescriptor mFd;
    Thread mThread;
    PyObject mitm;
    int mProxyPort;

    @Override
    public void onCreate() {
        Python py = Python.getInstance();
        mitm = py.getModule("mitm");
        Log.d(TAG, "mitm module: " + mitm);

        super.onCreate();
    }

    @Override
    public void onDestroy() {
        _stop();
        super.onDestroy();
    }

    class IncomingHandler extends Handler {
        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MitmAddon.MSG_START_MITM:
                    mProxyPort = msg.arg1;
                    mFd = (ParcelFileDescriptor)msg.obj;

                    if(mThread == null) {
                        mThread = new Thread(MitmService.this);
                        mThread.start();
                    } else
                        Log.w(TAG, "Thread already active");
                    break;
                case MitmAddon.MSG_GET_CA_CERTIFICATE:
                    if(mThread == null)
                        handleGetCaCertificate(msg.replyTo);
                    else
                        Log.w(TAG, "Not supported while mitm running");
                    break;
                default:
                    super.handleMessage(msg);
            }
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        mMessenger = new Messenger(new IncomingHandler());
        return mMessenger.getBinder();
    }

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        return START_NOT_STICKY;
    }

    private void _stop() {
        mitm.callAttr("stop");

        while((mThread != null) && (mThread.isAlive())) {
            try {
                mThread.join();
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
        }

        stopSelf();
    }

    @Override
    public void run() {
        mitm.callAttr("run", mFd.getFd(), mProxyPort);

        Log.d(TAG, "Done");
    }

    public void handleGetCaCertificate(Messenger replyTo) {
        String cert = null;

        PyObject pyres = mitm.callAttr("getCAcert");
        if(pyres != null)
            cert = pyres.toJava(String.class);

        if(replyTo != null) {
            Bundle bundle = new Bundle();
            bundle.putString(MitmAddon.CERTIFICATE_RESULT, cert);
            Message msg = Message.obtain(null, MitmAddon.MSG_GET_CA_CERTIFICATE);
            msg.setData(bundle);

            try {
                replyTo.send(msg);
            } catch (RemoteException e) {
                e.printStackTrace();
            }
        }
    }
}
