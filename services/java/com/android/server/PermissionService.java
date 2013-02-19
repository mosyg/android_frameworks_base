/*PermissionService.java */
package com.android.server;

import android.os.IPermissionService;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.Process;
import android.util.Log;
import android.os.Process;

public class PermissionService extends IPermissionService.Stub {
    private static final String TAG = "PermissionService";
    private PermissionWorkerThread mWorker;
    private PermissionWorkerHandler mHandler;
    private Context mContext;

    public PermissionService(Context context) {
        super();
        mContext = context;
        mWorker = new PermissionWorkerThread("PermissionServiceWorker");
        mWorker.start();
        Log.i(TAG, "Spawned worker thread");
    }
 
    public void postEvent(String jsonEvent) {
        Log.i(TAG, "setValue " + jsonEvent.length() + " in PID: "+Process.myPid());
		
        //Message msg = Message.obtain();
        //msg.what = PermissionWorkerHandler.MESSAGE_SET;
        //msg.arg1 = val;
        //mHandler.sendMessage(msg);
    }
 
    private class PermissionWorkerThread extends Thread {
        public PermissionWorkerThread(String name) {
            super(name);
        }
        public void run() {
            Looper.prepare();
            mHandler = new PermissionWorkerHandler();
            Looper.loop();
        }
    }
 
    private class PermissionWorkerHandler extends Handler {
        private static final int MESSAGE_SET = 0;
        @Override
        public void handleMessage(Message msg) {
            try {
                if (msg.what == MESSAGE_SET) {
                    Log.i(TAG, "set message received: " + msg.arg1 + " in PID: "+Process.myPid());
                }
            } catch (Exception e) {
                // Log, don't crash!
                Log.e(TAG, "Exception in PermissionWorkerHandler.handleMessage:", e);
            }
        }
    }
}

