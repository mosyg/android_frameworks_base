/*PermissionLogger.java */
package com.android.server;

import android.app.PermissionsManager.PermissionEvent;

import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Calendar;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;

import android.content.Context;
import android.content.SharedPreferences;
import android.content.SharedPreferences.Editor;
import android.content.pm.PackageManager;
import android.os.Environment;
import android.os.Process;
import android.util.Log;

public class PermissionLogger {
    public final static String TAG = "AndroMEDA";
    
    private final static String SHARED_PREFS = "AndroMEDA-Logging-Prefs";
    private final static String UPLOADED_RECORD = "AndroMEDA-Logging-Records";
    private final static String KEY_UUID = "uuid";
    private final static String KEY_UPLOAD_URL = "uploadurl";
    private final static String KEY_BACKLOG_DAYS = "backlogdays";
    private final static String KEY_SHOULD_UPLOAD = "shouldupload";
    
    private Object lock = new Object();
    private Context mContext;
    private File outdir;
    private SharedPreferences prefs;
    //Shared preferences to store upload information
    private SharedPreferences uploaded;
        
    private String uuid = "";
    private long lastUpload = 0;
    private long lastCleanup = 0;

    /**
     * Number of days to log until the old ones get deleted
     */
    int numDaysBacklog = 7;
    
    boolean shouldUpload = true; //change this to false for anything but research mode
    String uploadURL = "http://srgnhl.cs.illinois.edu/andromeda/upload_logs.php";
    
    HashSet<String> ignoredPackages = new HashSet<String>(Arrays.asList(new String[] {
             "com.android.systemui",
             "com.google.android.location",
             "com.google.android.syncadapters.contacts",
             "com.android.providers.downloads",
             "com.android.vending",
             "com.google.android.apps.uploader"
             }));
    
    
    public PermissionLogger(Context context) {
        mContext = context;
        createOutDirectory();
        initPreferences();
    }
    
    /**
     * Recreate the output directory. it may be deleted whenever.
     */
    private void createOutDirectory() {
        //outdir = new File(Environment.getDataDirectory(), "AndroMEDA");
        outdir = new File(Environment.getExternalStorageDirectory(), "AndroMEDA");
        outdir.setReadable(true, false);
        outdir.mkdirs();
    }
    
    
    private int getDay() {
        Calendar c = Calendar.getInstance(); 
        //return c.get(Calendar.DAY_OF_YEAR);
        //that loops after 365, which doesn't do us a lot of good. let's do it since the epoch.
        long millis = c.getTimeInMillis();
        return getDay(millis);
    }
    private int getDay(long millis) {
        long days = millis / (1000 * 60 * 60 * 24);
        return (int)days;
    }


    private String getFilename(String packagename) {
        return getFilename(packagename, 0);
    }
    private String getFilename(String packagename, int past) {
        return packagename+"-"+(getDay()-past)+".json";
    }

    
    public void log(ConcurrentLinkedQueue<PermissionEvent> eventList) throws Exception  {
        HashMap<String,ArrayList<PermissionEvent>> eventsByPackage = new HashMap<String,ArrayList<PermissionEvent>>();
        createOutDirectory();
        
        // throw all of the items in the eventList into sorted buckets
        PermissionEvent next = null;
        while ( (next = eventList.poll()) != null) {
            if (shouldLogApp(next)) {
                PermissionEvent event = next;
                String key = "unknown";
                if (event.packagenames != null && event.packagenames.length > 0)
                    key = event.packagenames[0];
                if (eventsByPackage.containsKey(key) == false)
                    eventsByPackage.put(key, new ArrayList<PermissionEvent>());
                eventsByPackage.get(key).add(event);
            }
        }
       
        synchronized (lock) {   
            // Log each bucket individually
            for (Map.Entry<String,ArrayList<PermissionEvent>> pair : eventsByPackage.entrySet()) {
                File outfile = new File(outdir,getFilename(pair.getKey()));
                outfile.setReadable(true, false);
                FileWriter writer = new FileWriter(outfile, true);
                for ( PermissionEvent event : pair.getValue()) {
                    writer.append(event.toJSON().toString());
                    writer.append("\n");
                }
                writer.flush();
                writer.close();
            }
        }
    }

    public boolean shouldLogApp(PermissionEvent event) {
        if ((Process.FIRST_APPLICATION_UID <= event.uid && event.uid <= Process.LAST_APPLICATION_UID) == false)
            return false;
        if (event.packagenames == null)
            return false;
        for (String ep : event.packagenames) {
            if (ep != null && ignoredPackages.contains(ep)) {
                // Log.i(TAG, "Filtering out package "+ep);
                return false;
            }
        }
        return true;
    }
    
    
    public void uploadIfAfter(long time) {
        if (System.currentTimeMillis() - lastUpload > time) {
            upload();
        }
    }
    
    public void upload() {
        for (File child : outdir.listFiles()) {
            if (hasUploaded(child) == false) {
                try {
                    doUpload(child);
                } catch (IOException e) {
                    e.printStackTrace();
                    //try again later.
                }
            }
        }
        lastUpload = System.currentTimeMillis();
    }
    
    public void cleanupIfAfter(long time) {
        if (System.currentTimeMillis() - lastUpload > time) {
            cleanup();
        }
    }
    
    public void cleanup() {
        int today = getDay();
        ArrayList<File> toDelete = new ArrayList<File>();
        synchronized(lock) {
            createOutDirectory();
            for (File child : outdir.listFiles()) {
                if ( (today - getDay(child.lastModified())) < numDaysBacklog) {
                    toDelete.add(child);
                }
            }
            for (File child : toDelete) {
                child.delete();
                removeUploaded(child);
            }
        }
        lastCleanup = System.currentTimeMillis();
    }
    
    
    public List<String> getEvents(String packagename, ConcurrentLinkedQueue<PermissionEvent> pendingEvents) throws IOException {
        if (mContext.checkCallingPermission("android.permission.GET_PERMISSION_HISTORY") == PackageManager.PERMISSION_DENIED) return null;
        
        List<String> lines = new ArrayList<String>();
        
        synchronized (lock) {       
            for (int i=numDaysBacklog-1; i>=0; i--) {
                try {
                    File outfile = new File(outdir,getFilename(packagename, i));
                    BufferedReader reader = new BufferedReader(new FileReader(outfile));
                    for (String line=reader.readLine(); line != null; line=reader.readLine()) {
                        lines.add(line);
                    }
                } catch (IOException e) {
                    e.printStackTrace();
                    //pass. some files may get cleaned up, but that's not a concern to us.
                }
            }
        }
        try {
            for (PermissionEvent event : pendingEvents) {
                if (event.packagenames != null && event.packagenames.length > 0) {
                    String key = event.packagenames[0];
                    if (packagename.equals(key)) {
                        lines.add(event.toJSON().toString());
                    }
                }
            }
        } catch (Exception e) {
            e.printStackTrace();
        }
        return lines;
    }

    
    
    
    
    
    
    
    
    
    
    /**** Preferences Stuff ****/
    
    void initPreferences() {
        prefs = mContext.getSharedPreferences(SHARED_PREFS, 0);
        uploaded = mContext.getSharedPreferences(UPLOADED_RECORD, 0);
        //make a UUID if we don't have one yet
        if (prefs.getString(KEY_UUID, "").equals("")){
            putString(prefs, KEY_UUID, UUID.randomUUID().toString());
        }
        readInPreferences();
    }
    
    void putString(SharedPreferences prefs, String key, String value) {
        Editor e = prefs.edit();
        e.putString(key, value);
        e.apply();
    }
    
    
    void readInPreferences() {
        uuid = prefs.getString(KEY_UUID, uuid);
        uploadURL = prefs.getString(KEY_UPLOAD_URL, uploadURL);
        numDaysBacklog = prefs.getInt(KEY_BACKLOG_DAYS, numDaysBacklog);
        shouldUpload = prefs.getBoolean(KEY_SHOULD_UPLOAD, shouldUpload);
    }
    
    
    
    
    
    
    
    
    
    
    
    /**** Upload Code ****/
    
    
    boolean hasUploaded(File file) {
        return uploaded.getBoolean(file.getName(), false);
    }
    
    void setHasUploaded(File file) {
        Editor e = uploaded.edit();
        e.putBoolean(file.getName(), true);
        e.commit();
    }
    
    void removeUploaded(File file) {
        Editor e = uploaded.edit();
        e.remove(file.getName());
        e.commit();
    }

    void doUpload(File file) throws IOException {
        HttpFileUploader uploader = new HttpFileUploader(uploadURL, "", makeFilename(file));
        int response = uploader.doUpload(new FileInputStream(file));
        if (response >= 200 && response <= 300) { //make sure the server accepted the file
            setHasUploaded(file);
        }
    }
    
    String makeFilename(File file) {
        return uuid+"___"+file.getName();
    }
    
    
    
    public class HttpFileUploader {

        URL connectURL;
        String params;
        String responseString;
        String fileName;
        byte[] dataToServer;

        HttpFileUploader(String urlString, String params, String fileName) {
            try {
                connectURL = new URL(urlString);
            } catch (Exception e) {
                e.printStackTrace();
            }
            this.params = params + "=";
            this.fileName = fileName;
        }

        int doUpload(FileInputStream stream) throws IOException {
            fileInputStream = stream;
            return upload();
        }

        FileInputStream fileInputStream = null;

        int upload() throws IOException {
            String exsistingFileName = fileName;

            String lineEnd = "\r\n";
            String twoHyphens = "--";
            String boundary = "*****";
 
            // ------------------ CLIENT REQUEST

            Log.e(TAG, "Starting to bad things");
            // Open a HTTP connection to the URL

            HttpURLConnection conn = (HttpURLConnection) connectURL.openConnection();

            // Allow Inputs
            conn.setDoInput(true);

            // Allow Outputs
            conn.setDoOutput(true);

            // Don't use a cached copy.
            conn.setUseCaches(false);

            // Use a post method.
            conn.setRequestMethod("POST");

            conn.setRequestProperty("Connection", "Keep-Alive");

            conn.setRequestProperty("Content-Type",
                    "multipart/form-data;boundary=" + boundary);

            DataOutputStream dos = new DataOutputStream(conn.getOutputStream());

            dos.writeBytes(twoHyphens + boundary + lineEnd);
            dos.writeBytes("Content-Disposition: form-data; name=\"uploadedfile\";filename=\"" + exsistingFileName + "\"" + lineEnd);
            dos.writeBytes(lineEnd);

            Log.e(TAG, "Headers are written");

            // create a buffer of maximum size

            int bytesAvailable = fileInputStream.available();
            int maxBufferSize = 1024;
            int bufferSize = Math.min(bytesAvailable, maxBufferSize);
            byte[] buffer = new byte[bufferSize];

            // read file and write it into form...

            int bytesRead = fileInputStream.read(buffer, 0, bufferSize);

            while (bytesRead > 0) {
                dos.write(buffer, 0, bufferSize);
                bytesAvailable = fileInputStream.available();
                bufferSize = Math.min(bytesAvailable, maxBufferSize);
                bytesRead = fileInputStream.read(buffer, 0, bufferSize);
            }

            // send multipart form data necesssary after file data...

            dos.writeBytes(lineEnd);
            dos.writeBytes(twoHyphens + boundary + twoHyphens + lineEnd);

            // close streams
            //Log.e(TAG, "File is written");
            fileInputStream.close();
            dos.flush();

            InputStream is = conn.getInputStream();
            // retrieve the response from server
            int ch;

            StringBuffer b = new StringBuffer();
            while ((ch = is.read()) != -1) {
                b.append((char) ch);
            }
            String s = b.toString();
            Log.i(TAG, "Uploaded. Response: "+s);
            dos.close();
            
            return conn.getResponseCode();

        }

    }

}

