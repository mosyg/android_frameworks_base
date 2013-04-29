/*PermissionService.java */
package com.android.server;

import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.net.InetAddress;
import java.net.URL;
import java.net.URL.URLExecuteListener;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HashMap;
import java.util.Arrays;
import java.util.LinkedList;
import java.util.List;
import java.util.Random;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.io.ByteArrayOutputStream;
import java.lang.CharSequence;
import java.lang.StringBuilder;
import java.io.*;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;


import com.android.internal.R;
import android.app.Notification;
import android.app.NotificationManager;
import android.os.UserHandle;
import android.app.PendingIntent;
import android.net.Uri;
import android.content.Context;
import android.content.Intent;
import android.os.Environment;
import android.os.IPermissionService;
import android.os.Handler;
import android.os.Looper;
import android.os.Message;
import android.os.Process;
import android.util.Log;
import android.app.PermissionsManager;
import android.app.PermissionsManager.PermissionEvent;
import android.os.Process;
import android.content.pm.ApplicationInfo;
import android.content.pm.PackageManager;

public class PermissionService extends IPermissionService.Stub {
    private static final String TAG = "AndroMEDA";
    private PermissionWorkerThread mWorker;
    private PermissionWorkerHandler mHandler;
    private WriteThread mWriter;
    private Context mContext;
    private ConcurrentLinkedQueue<PermissionEvent> eventList = new ConcurrentLinkedQueue<PermissionEvent>();
    private ConcurrentHashMap<Integer,String[]> knownUids = new ConcurrentHashMap<Integer,String[]>();

    private Notification mNotification;    
    private PermissionLogger logger;
    



    public PermissionService(Context context) {
        super();
        mContext = context;
        mWorker = new PermissionWorkerThread("PermissionServiceWorker");
        mWorker.start();
        mWriter = new WriteThread();
        mWriter.start();

        logger = new PermissionLogger(context);

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
        if (uid >= 10000)
            Log.i(TAG, "postNewEvent with all the details: "+permission+" message: "+message);
        PermissionEvent event = new PermissionEvent(permission, message, uid, selfToo, resultOfCheck, time, getPackageNameForUid(uid, mContext), data);
        eventList.add(event);
        process(event);
    }
    
    private String[] getPackageNameForUid(int uid, Context context) {
        //if (knownUids.containsKey(uid)) {
        //    return knownUids.get(uid);
        //}
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

    private CharSequence getAppName(Context context, PermissionEvent event) {
        if (event.packagenames == null || event.packagenames.length == 0) return ""; 
        try {
            PackageManager pm = context.getPackageManager();
            ApplicationInfo appinfo = pm.getApplicationInfo(event.packagenames[0], 0);
            return pm.getApplicationLabel(appinfo);
        } catch (Exception e) {
            e.printStackTrace();
            return "Unknown";
        }

    }

    private class WriteThread extends Thread {
        int count = 0;
        public void run() {
            while (true) {
                try {
                    logger.log(eventList);
                    logger.uploadIfAfter(1000 * 60 * 60); //upload every hour
                    logger.cleanupIfAfter(1000 * 60 * 60 * 12); //cleanup every 12 hours
                } catch (Exception e) {
                    e.printStackTrace();
                }

                try {
                    Thread.sleep(4*1000L);
                } catch (Exception e) {
                    //don't care
                }
                count += 1;
            }
        }
    }

    private void process(PermissionEvent event) {
        if (logger.shouldLogApp(event) == false) return; //don't care about user app
        for (Detector detector : rules) {
            detector.process(event);
        }
    }

    private void postNewSecurityEvent(SecurityEvent event) {
        Message msg = Message.obtain();
        msg.what = PermissionWorkerHandler.MESSAGE_DISPLAY;
        msg.obj = event; 
        mHandler.sendMessage(msg);
    }


    public List<String> getEvents(String packagename) {
        try {
            return logger.getEvents(packagename, eventList);
        } catch (Exception e) {
            return new ArrayList<String>();
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
        private static final int MESSAGE_DISPLAY = 0;
        private static final int MESSAGE_DISMISS = 1;

        private HashMap<String, HashSet<SecurityEvent>> ongoingEventsMap = new HashMap<String, HashSet<SecurityEvent>>();
        private HashMap<String, Integer> ongoingNotifications = new HashMap<String, Integer>();

        private int maxeventid = 0;
        


        @Override
        public void handleMessage(Message msg) {
            try {
                if (msg.what == MESSAGE_DISPLAY) {
                    //Log.i(TAG, "set message received: " + msg.arg1 + " in PID: "+Process.myPid());
                    processSecurityEvent((SecurityEvent)msg.obj);
                } else if (msg.what == MESSAGE_DISMISS) {
                    dismissSecurityEvent((SecurityEvent)msg.obj);
                }
            } catch (Exception e) {
                // Log, don't crash!
                Log.e(TAG, "Exception in PermissionWorkerHandler.handleMessage:", e);
            }
        }

        private void updateMaps(SecurityEvent event) {
            if (ongoingEventsMap.containsKey(event.packagename) == false) 
                ongoingEventsMap.put(event.packagename, new HashSet<SecurityEvent>());
            if (ongoingNotifications.containsKey(event.packagename) == false)  {
                ongoingNotifications.put(event.packagename, maxeventid);
                maxeventid += 1;
            }
        }

        private void processSecurityEvent(SecurityEvent event) {
            updateMaps(event);
            HashSet<SecurityEvent> ongoingEvents = ongoingEventsMap.get(event.packagename);
            ongoingEvents.add(event);
            long timeout = determineTimeout(event);
            if (timeout > 0) {
                Message msg = Message.obtain();
                msg.what = PermissionWorkerHandler.MESSAGE_DISMISS;
                msg.obj = event; 
                mHandler.sendMessageDelayed(msg, timeout);
            }
            setNotification(ongoingEvents, ongoingNotifications.get(event.packagename));
        }


        private long determineTimeout(SecurityEvent event) {
            if (event.severity == SEVERITY_LOW) return 3000;
            if (event.severity == SEVERITY_MED) return 1000*15;
            if (event.severity >= SEVERITY_HIGH) return -1;
            else return -1;
        }

        private void dismissSecurityEvent(SecurityEvent event) {
            updateMaps(event);
            HashSet<SecurityEvent> ongoingEvents = ongoingEventsMap.get(event.packagename);
            ongoingEvents.remove(event);
            setNotification(ongoingEvents, ongoingNotifications.get(event.packagename));
        }

        private void setNotification(Set<SecurityEvent> ongoingEvents, int currentid) {
            NotificationManager notificationManager = (NotificationManager) mContext.getSystemService(Context.NOTIFICATION_SERVICE);
            if (notificationManager == null) {
                return;
            }
            if (ongoingEvents.size() <= 0) {
                notificationManager.cancel(currentid);
                Log.d(TAG, "Dismissing notification with notifyId:"+currentid);
                return;
            }

            String title = getTitle(ongoingEvents);//"Permission"; //getNotifTitle(notif, mContext);
            String message = getMessage(ongoingEvents); //"A permission is being used"; //getNotifMessage(notif, mContext);
            String longmessage = getLongMessage(ongoingEvents); //"A permission is being used"; //getNotifMessage(notif, mContext);
            int icon = getIcon(ongoingEvents);

            Log.d(TAG, "setPermissionNotification, notifyId: " + currentid +
                    ", title: " + title +
                    ", message: " + message);

            // if not to popup dialog immediately, pending intent will open the dialog
            PendingIntent pi = PendingIntent.getActivity(mContext, (int)System.currentTimeMillis(), makeDialogIntent(ongoingEvents), 0);                

            // Construct Notification
            mNotification = new Notification.Builder(mContext)
                 .setContentTitle(title)
                 .setContentText(message)
                 .setSmallIcon(icon)
                 .setStyle(new Notification.BigTextStyle().bigText(longmessage))
                 .setContentIntent(pi)
                 .setAutoCancel(false)
                 .setOngoing(false)
                 .setSound(null)
                 .build();


            notificationManager.notifyAsUser(null, currentid, mNotification, UserHandle.ALL);

        }

        private Intent makeDialogIntent(Set<SecurityEvent> events) {
            List<SecurityEvent> severe = getMostSevere(events);
            Intent i = new Intent(Intent.ACTION_VIEW);
            i.setData(Uri.parse("permissions://"+severe.get(0).packagename));
            return i;
        }

        private List<SecurityEvent> getMostSevere(Set<SecurityEvent> events) {
            int maxseverity = -1000;
            ArrayList<SecurityEvent> eventsatlevel = new ArrayList<SecurityEvent>();
            for (SecurityEvent event : events) {
                if (maxseverity == event.severity) {
                    eventsatlevel.add(event);
                } else if (maxseverity < event.severity) {
                    maxseverity = event.severity;
                    eventsatlevel.clear();
                    eventsatlevel.add(event);
                } 
            }
            if (eventsatlevel.size() == 0) { Log.d(TAG, "getMostSevere returning 0 results. input size: "+events.size()); }
            return eventsatlevel;
        }
        private String getTitle(Set<SecurityEvent> events) {
            StringBuilder builder = new StringBuilder();
            List<SecurityEvent> severe = getMostSevere(events);
            builder.append(severe.get(0).permission.title);
            if (events.size() > 1) {
                builder.append(", more");
            }
            return builder.toString();
        }
        private String getMessage(Set<SecurityEvent> events) {
            StringBuilder builder = new StringBuilder();

            List<SecurityEvent> severe = getMostSevere(events);
            builder.append(severe.get(0).appname.toString());
            builder.append(" has ");
            builder.append(severe.get(0).permission.display);
            if (events.size() > 1) {
                builder.append(", and more");
            }
            return builder.toString();
        }
        private String getLongMessage(Set<SecurityEvent> events) {
            StringBuilder builder = new StringBuilder();

            List<SecurityEvent> severe = getMostSevere(events);
            builder.append(severe.get(0).appname.toString());
            builder.append(" has ");
            boolean first = true;
            for (SecurityEvent event : severe) {
                if (first == false) builder.append(", ");
                builder.append(event.permission.display);
                first = false;
            }
            return builder.toString();
        }

        private int getIcon(Set<SecurityEvent> events) {
            List<SecurityEvent> severe = getMostSevere(events);
            return severe.get(0).permission.icon;
        }

    }





    




    private final int SEVERITY_LOW = 1;
    private final int SEVERITY_MED = 2;
    private final int SEVERITY_HIGH = 3;
    private final int SEVERITY_SEVERE = 4;

    private class SecurityEvent {  
        long time;
        PermissionEvent perm;
        CharSequence appname;
        String packagename;
        int severity;
        Permission permission;
    }

    private static class Permission {
        String name;
        String display;
        String title;
        int icon;
        public Permission(String name, String title, String display, int icon) {
            this.name = name; 
            this.display = display; 
            this.title = title;
            this.icon = icon;
        }
    }


    public Detector[] rules = {
        new PermissionDetector(new Permission[] {
            new Permission("android.permission.READ_PHONE_STATE", "Phone Identity Accessed", "read your Phone Identity", com.android.internal.R.drawable.sys_access_phoneinfo_normal), 
            new Permission("android.permission.GET_ACCOUNTS", "Accounts Accessed", "read your Account info", com.android.internal.R.drawable.sys_access_personal_normal), 
            new Permission("android.permission.ACCESS_COARSE_LOCATION", "Location Accessed", "got your Location", com.android.internal.R.drawable.sys_access_phoneinfo_normal),
        }, SEVERITY_LOW),
        new PermissionDetector(new Permission[] {
            new Permission("android.permission.READ_CONTACTS", "Contacts Accessed", "read your Contacts", com.android.internal.R.drawable.sys_access_contacts_normal),
            new Permission("com.android.browser.permission.READ_HISTORY_BOOKMARKS", "Browser Accessed", "read your Browser History", com.android.internal.R.drawable.sys_access_personal_normal),
            new Permission("android.permission.READ_CALENDAR", "Calendar Accessed", "read your Calendar", com.android.internal.R.drawable.sys_access_events_normal),
            new Permission("android.permission.RECORD_AUDIO", "Mic Recording", "recorded your Phone's Microphone", com.android.internal.R.drawable.sys_access_mic_normal),
            //new Permission("android.permission.CAMERA", "Camera Recording", "recorded a video", com.android.internal.R.drawable.sys_access_mic_normal),
            new Permission("android.permission.READ_SMS", "Messages Accessed", "read your Messages", com.android.internal.R.drawable.sys_access_messages_normal),
            new Permission("android.permission.ACCESS_FINE_LOCATION", "GPS Location Accessed", "got your GPS Location", com.android.internal.R.drawable.sys_access_phoneinfo_normal),
        }, SEVERITY_MED),
        new PermissionDetector(new Permission[] {
            new Permission("android.permission.WRITE_CONTACTS", "Contacts Written", "wrote to your Contacts", com.android.internal.R.drawable.sys_write_contacts_normal),
            new Permission("android.permission.WRITE_CALENDAR", "Calendar Written", "wrote to your Calendar", com.android.internal.R.drawable.sys_write_events_normal),
            new Permission("android.permission.WRITE_SMS", "Messages Written", "wrote to your Messages", com.android.internal.R.drawable.sys_write_messages_normal),
        }, SEVERITY_HIGH),
    };






    private abstract class Detector {
        public abstract void process(PermissionEvent event);
    }
    private class PermissionDetector extends Detector {
        Permission[] permissions;
        int severity;
        public PermissionDetector(Permission[] permissions, int severity) {
            this.permissions = permissions;
            this.severity = severity;
        }

        public void process(PermissionEvent event) {
            for (Permission permission : permissions) {
                if (permission.name.equals(event.permission)) {
                    SecurityEvent secevent = new SecurityEvent();
                    secevent.time = event.time;
                    secevent.perm = event;
                    secevent.severity = severity;
                    secevent.permission = permission;
                    secevent.appname = getAppName(mContext, event); 
                    secevent.packagename = event.packagenames.length == 0 ? "unknown" : event.packagenames[0];
                    postNewSecurityEvent(secevent);
                }
            }
        }
    }
}

