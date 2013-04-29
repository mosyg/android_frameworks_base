/*PermissionLogger.java */
package com.android.server;

import android.app.PermissionsManager.PermissionEvent;

import java.io.BufferedReader;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
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
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;

import android.content.Context;
import android.content.pm.PackageManager;
import android.os.Environment;
import android.os.Process;
import android.util.Log;

public class PermissionLogger {
    public final static String TAG = "AndroMEDA";
    
    private final static String SHARED_PREFS = "prefs.prop";
    private final static String UPLOADED_RECORD = "upload_log.prop";
    private final static String KEY_UUID = "uuid";
    private final static String KEY_IGNORED_PACKAGES = "ignored";
    private final static String KEY_UPLOAD_URL = "uploadurl";
    private final static String KEY_BACKLOG_TIME = "backlogtime";
    private final static String KEY_SHOULD_UPLOAD = "shouldupload";
    private final static String KEY_TIME_UNIT = "timeunit";
    private final static String KEY_UPLOAD_TIME = "uploadtime";
    
    private Object lock = new Object();
    private Context mContext;
    private File maindir;
    private File logdir;
    private Properties prefs = new Properties();
    //Shared preferences to store upload information
    private Properties uploaded = new Properties();
        
    private String uuid = "";
    private long lastUpload = 0;
    private long lastCleanup = 0;

    /**
     * Number of days to log until the old ones get deleted
     */
    int numTimeUnitsBacklog = 24;//3;
    
    public long millisPerTimeUnit = 1000 * 60 * 60;// * 24; // main time unit block.
    
    public long millisUntilUpload = millisPerTimeUnit * 1;
    
    boolean shouldUpload = true; //change this to false for anything but research mode
    String uploadURL = "http://srgnhl.cs.illinois.edu/andromeda/upload_logs.php";
    
    List<String> defaultIgnoredPackages = Arrays.asList(new String[] {
             "com.android.systemui",
             "com.google.android.location",
             "com.google.android.syncadapters.contacts",
             "com.android.providers.downloads",
             "com.android.vending",
             "com.google.android.apps.uploader"
    });
    HashSet<String> ignoredPackages = new HashSet<String>(defaultIgnoredPackages);
    
    
    public PermissionLogger(Context context) {
        mContext = context;
        createOutDirectory();
        initPreferences();
    }
    
    /**
     * Recreate the output directory. it may be deleted whenever.
     */
    private void createOutDirectory() {
        maindir = new File(Environment.getDataDirectory(), "AndroMEDA");
        maindir.mkdirs();
        maindir.setReadable(true, false);
        logdir = new File(maindir, "logs");
        //outdir = new File(Environment.getExternalStorageDirectory(), "AndroMEDA");
        logdir.setReadable(true, false);
        logdir.mkdirs();
    }
    
    
    private int getTimeBlock() {
        Calendar c = Calendar.getInstance(); 
        //return c.get(Calendar.DAY_OF_YEAR);
        //that loops after 365, which doesn't do us a lot of good. let's do it since the epoch.
        long millis = c.getTimeInMillis();
        return getTimeBlock(millis);
    }
    private int getTimeBlock(long millis) {
        long days = millis / (millisPerTimeUnit);
        return (int)days;
    }


    private String getFilename(String packagename) {
        return getFilename(packagename, 0);
    }
    private String getFilename(String packagename, int past) {
        return packagename+"-"+(getTimeBlock()-past)+".json";
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
                File outfile = new File(logdir,getFilename(pair.getKey()));
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
    
    
    public void uploadIfAfter(long time, boolean all) {
        if (System.currentTimeMillis() - lastUpload > time) {
            upload(all);
        }
    }
    
    public void upload(boolean all) {
        for (File child : logdir.listFiles()) {
            if (all || (hasUploaded(child) == false && System.currentTimeMillis() - child.lastModified() > millisUntilUpload) ) {
                try {
                    if (all) {
                        doUpload(child, false);
                    } else {
                        doUpload(child, true);
                    }
                } catch (IOException e) {
                    e.printStackTrace();
                    //try again later.
                }
            }
            commitUploaded();
        }
        lastUpload = System.currentTimeMillis();
    }
    
    public void cleanupIfAfter(long time, boolean all) {
        if (System.currentTimeMillis() - lastCleanup > time) {
            cleanup(all);
        }
    }
    
    public void cleanup(boolean all) {
        int now = getTimeBlock();
        ArrayList<File> toDelete = new ArrayList<File>();
        synchronized(lock) {
            createOutDirectory();
            for (File child : logdir.listFiles()) {
                if (all || (now - getTimeBlock(child.lastModified())) < (numTimeUnitsBacklog)) {
                    toDelete.add(child);
                }
            }
            for (File child : toDelete) {
                child.delete();
                removeUploaded(child);
            }
            commitUploaded();
        }
        lastCleanup = System.currentTimeMillis();
    }
    
    
    public List<String> getEvents(String packagename, ConcurrentLinkedQueue<PermissionEvent> pendingEvents) throws IOException {
        if (mContext.checkCallingPermission("android.permission.GET_PERMISSION_HISTORY") == PackageManager.PERMISSION_DENIED) return null;
        
        List<String> lines = new ArrayList<String>();
        
        synchronized (lock) {       
            for (int i=numTimeUnitsBacklog-1; i>=0; i--) {
                try {
                    File outfile = new File(logdir,getFilename(packagename, i));
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
        loadPrefs(prefs, new File(maindir, SHARED_PREFS));
        
        loadPrefs(uploaded,new File(maindir, UPLOADED_RECORD));     
        
        //make a UUID if we don't have one yet
        if (prefs.getProperty(KEY_UUID, "").equals("")){
            putString(KEY_UUID, UUID.randomUUID().toString());
        }
        readInPreferences();
    }
    
    void loadPrefs(Properties prefs, File file) {
        try {
            FileInputStream fis = new FileInputStream(file);
            prefs.load(fis);
            fis.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
    
    void putString(String key, String value) {
        try {
            prefs.setProperty(key, value);
            FileOutputStream fos = new FileOutputStream(new File(maindir, SHARED_PREFS));
            prefs.store(fos, "preferences for AndroMEDA");
            fos.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
    
    
    
    
    void readInPreferences() {
        try {
            uuid = prefs.getProperty(KEY_UUID, uuid);
            uploadURL = prefs.getProperty(KEY_UPLOAD_URL, uploadURL);
            numTimeUnitsBacklog = Integer.parseInt(prefs.getProperty(KEY_BACKLOG_TIME, ""+numTimeUnitsBacklog));
            shouldUpload = Boolean.parseBoolean(prefs.getProperty(KEY_SHOULD_UPLOAD, ""+shouldUpload));
            millisPerTimeUnit = Long.parseLong(prefs.getProperty(KEY_TIME_UNIT, ""+millisPerTimeUnit));
            millisUntilUpload = Long.parseLong(prefs.getProperty(KEY_UPLOAD_TIME, ""+millisUntilUpload));
            ignoredPackages = makeIgnoreList();
        } catch (Exception e) {
            e.printStackTrace();
        }
    }
    
    HashSet<String> makeIgnoreList() {
        String allignored = prefs.getProperty(KEY_IGNORED_PACKAGES, "");
        List<String> ignoredlist = new ArrayList<String>();
        if ("".equals(allignored)) {
            ignoredlist = defaultIgnoredPackages;
        } else {
            allignored.split(",");
        }
        return new HashSet<String>(ignoredlist);
    }

    String getUploadUrl() {
        return uploadURL;
    }
    void setUploadUrl(String url) {
        putString(KEY_UPLOAD_URL, url);
        readInPreferences();
    }
    void setTimeUnits(long time) {
        putString(KEY_TIME_UNIT, ""+time);
        readInPreferences();
    }
    long getTimeUnits() {
        return millisPerTimeUnit;
    }
    void setUploadTime(long time) {
        putString(KEY_UPLOAD_TIME, ""+time);
        readInPreferences();
    }
    long getUploadTime() {
        return millisUntilUpload;
    }
    void setBacklogTime(int backlog) {
        putString(KEY_BACKLOG_TIME, ""+backlog);
        readInPreferences();
    }
    long getBacklogTime() {
        return numTimeUnitsBacklog;
    }
    void setEnableUpload(boolean enable) {
        putString(KEY_SHOULD_UPLOAD, ""+enable);
        readInPreferences();
    }
    boolean getEnableUpload() {
        return shouldUpload;
    }
    
    void saveIgnoreList() {
        StringBuilder b = new StringBuilder();
        boolean notfirst = false;
        for (String packagename : ignoredPackages) {
            if (notfirst) b.append(",");
            b.append(packagename);
            notfirst = true;
        }
        putString(KEY_IGNORED_PACKAGES, b.toString());
    }
    List<String> getIgnoredPackages() {
        return Arrays.asList(ignoredPackages.toArray(new String[] {}));
    }
    
    void addToIgnoreList(String packagename) {
        ignoredPackages.add(packagename);
        saveIgnoreList();
    }
    
    void removeFromIgnoreList(String packagename) {
        ignoredPackages.remove(packagename);
        saveIgnoreList();
    }
    
    
    
    
    
    
    
    
    
    
    
    
    /**** Upload Code ****/
    
    
    boolean hasUploaded(File file) {
        return uploaded.getProperty(file.getName()) != null;
    }
    
    void setHasUploaded(File file) {
        uploaded.setProperty(file.getName(), "uploaded");
    }
    
    void removeUploaded(File file) {
        uploaded.remove(file.getName());
    }
    void commitUploaded() {
        try {
            FileOutputStream fos = new FileOutputStream(new File(maindir, UPLOADED_RECORD));
            uploaded.store(fos, "upload logs for AndroMEDA");
            fos.close();
        } catch (IOException e) {
            e.printStackTrace();
        }
    }


    void doUpload(File file, boolean logUploaded) throws IOException {
        HttpFileUploader uploader = new HttpFileUploader(uploadURL, "", makeFilename(file));
        int response = uploader.doUpload(new FileInputStream(file));
        if (logUploaded && (response >= 200 && response <= 300)) { //make sure the server accepted the file
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
            Log.d(TAG, "Starting to upload "+fileName+" to "+connectURL.toString());
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
            conn.setRequestProperty("Content-Type","multipart/form-data;boundary=" + boundary);
            DataOutputStream dos = new DataOutputStream(conn.getOutputStream());
            dos.writeBytes(twoHyphens + boundary + lineEnd);
            dos.writeBytes("Content-Disposition: form-data; name=\"uploadedfile\";filename=\"" + exsistingFileName + "\"" + lineEnd);
            dos.writeBytes(lineEnd);

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
            Log.d(TAG, "Upload complete. Response: "+s);
            dos.close();
            
            return conn.getResponseCode();
        }
    }

}

