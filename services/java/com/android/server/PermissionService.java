/*PermissionService.java */
package com.android.server;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.net.InetAddress;
import java.net.URL;
import java.net.URL.URLExecuteListener;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedList;
import java.util.Random;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.io.ByteArrayOutputStream;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;


import android.os.Environment;
import android.os.IPermissionService;
import android.content.Context;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.Process;
import android.util.Log;
import android.app.PermissionsManager;
import android.app.PermissionsManager.PermissionEvent;
import android.os.Process;

public class PermissionService extends IPermissionService.Stub {
    private static final String TAG = "PermissionService";
    private PermissionWorkerThread mWorker;
    private PermissionWorkerHandler mHandler;
    private WriteThread mWriter;
    private Context mContext;
    private ConcurrentLinkedQueue<PermissionEvent> eventList = new ConcurrentLinkedQueue<PermissionEvent>();
    private ConcurrentHashMap<Integer,String[]> knownUids = new ConcurrentHashMap<Integer,String[]>();

    public PermissionService(Context context) {
        super();
        mContext = context;
        mWorker = new PermissionWorkerThread("PermissionServiceWorker");
        mWorker.start();
        mWriter = new WriteThread();
        mWriter.start();

        Log.i(TAG, "Spawned worker thread");
    }
 
    /*
    public void postEvent(String jsonEvent) {
        Log.i(TAG, "postEvent " + jsonEvent.length() + " in PID: "+Process.myPid());
        try {
            PermissionEvent evt = PermissionEvent.fromJSON(jsonEvent);
            Log.i(TAG, "postEvent " + evt + " finished in PID: "+Process.myPid());
            eventList.add(evt);
        } catch (Exception e) {
            e.printStackTrace();
        }

        //Message msg = Message.obtain();
        //msg.what = PermissionWorkerHandler.MESSAGE_SET;
        //msg.arg1 = val;
        //mHandler.sendMessage(msg);
    }
    */
 
    public void postNewEvent(String permission, String message, int uid, boolean selfToo, int resultOfCheck, long time, String data) {
        Log.i(TAG, "postNewEvent with all the details: "+permission+" message: "+message);
        PermissionEvent event = new PermissionEvent(permission, message+" - 2", uid, selfToo, resultOfCheck, time, getPackageNameForUid(uid, mContext), data);
        eventList.add(event);
    }
    
    private String[] getPackageNameForUid(int uid, Context context) {
        if (knownUids.containsKey(uid)) {
            return knownUids.get(uid);
        }
        if (context != null) {
            String[] packages = context.getPackageManager().getPackagesForUid(uid);
            if (packages != null && packages.length >= 1) {
                knownUids.put(uid, packages);
            }
            return packages;
        } else {
            return new String[] { "unknown" };
        }
    }







    private class WriteThread extends Thread {
        public void run() {
            while (true) {
                try {
                    Log.i(TAG, "Starting write in global thread");
                    File outdir = new File(Environment.getDataDirectory(), "APM");
                    outdir.setReadable(true, false);
                    outdir.mkdirs();
                    File outfile = new File(outdir,"all.json");
                    outfile.setReadable(true, false);
                    FileWriter writer = new FileWriter(outfile, true);
                    PermissionEvent next = null;
                    while ( (next = eventList.poll()) != null) {
                        Log.i(TAG, "writing item in global thread "+next.message);
                        writer.append(next.toJSON().toString(4));
                        writer.append("\n");
                    }
                    //for (String data : pendingOutput) writer.append(data);
                    writer.flush();
                    writer.close();
                    Log.i(TAG, "finishing write in global thread to "+outfile.getAbsolutePath());
                } catch (Exception e) {
                    e.printStackTrace();
                }

                try {
                    Thread.sleep(4*1000L);
                } catch (Exception e) {
                    //don't care
                }
            }
        }
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

