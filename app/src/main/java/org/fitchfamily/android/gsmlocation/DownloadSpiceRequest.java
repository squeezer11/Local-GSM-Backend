package org.fitchfamily.android.gsmlocation;

import android.content.Context;
import android.net.wifi.WifiManager;
import android.os.PowerManager;
import android.text.TextUtils;
import android.util.Log;

import com.octo.android.robospice.SpiceManager;
import com.octo.android.robospice.persistence.DurationInMillis;
import com.octo.android.robospice.persistence.exception.SpiceException;
import com.octo.android.robospice.request.SpiceRequest;
import com.octo.android.robospice.request.listener.RequestListener;
import com.octo.android.robospice.retry.DefaultRetryPolicy;

import java.io.BufferedInputStream;
import java.net.HttpURLConnection;
import java.net.MalformedURLException;
import java.net.URL;
import java.text.SimpleDateFormat;
import java.util.Arrays;
import java.util.Date;
import java.util.LinkedList;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;
import java.util.zip.GZIPInputStream;

import javax.net.ssl.HttpsURLConnection;

import static org.fitchfamily.android.gsmlocation.LogUtils.makeLogTag;

/**
 * Background tasks gathers data from OpenCellId and/or Mozilla Location
 * services and produces a new database file in the name specified in the
 * Config class. We don't actually touch the file being used by
 * the actual tower lookup.
 *
 * If/when the tower lookup is called, it will check for the existence of
 * the new file and if so, close the file it is using, purge its caches,
 * and then move the old file to backup and the new file to active.
 */

public class DownloadSpiceRequest extends SpiceRequest<DownloadSpiceRequest.Result> {
    public static void executeWith(BaseActivity activity) {
        executeWith(activity, activity.getSpiceManager());
    }

    public static void executeWith(Context context, SpiceManager spiceManager) {
        spiceManager.execute(new DownloadSpiceRequest(context.getApplicationContext()), DownloadSpiceRequest.CACHE_KEY, DurationInMillis.ALWAYS_EXPIRED, new RequestListener<Result>() {
            @Override
            public void onRequestFailure(SpiceException spiceException) {
                // ignore
            }

            @Override
            public void onRequestSuccess(DownloadSpiceRequest.Result result) {
                // ignore
            }
        });
    }

    public static final class Result {

    }

    public static DownloadSpiceRequest lastInstance = null; // bad style, but there should never be more than one instance

    public static final String CACHE_KEY = "DownloadSpiceRequest";

    private static final String TAG = makeLogTag(DownloadSpiceRequest.class);
    private static final boolean DEBUG = Config.DEBUG;
    private static final int TRANSACTION_SIZE_LIMIT = 1000;

    private final Context context;
    private boolean[] mccFilter = new boolean[1000];
    private boolean[] mncFilter = new boolean[1000];
    private DatabaseCreator databaseCreator;
    private final PowerManager.WakeLock wakeLock;
    private final WifiManager.WifiLock wifiLock;
    private StringBuffer logBuilder = new StringBuffer();
    private String lastProgressMessage;
    private int lastProgress;

    public DownloadSpiceRequest(Context context) {
        super(Result.class);
        this.context = context.getApplicationContext();
        wakeLock = ((PowerManager) this.context.getSystemService(Context.POWER_SERVICE)).newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, CACHE_KEY);
        wifiLock = ((WifiManager) this.context.getSystemService(Context.WIFI_SERVICE)).createWifiLock(CACHE_KEY);
        setRetryPolicy(new DefaultRetryPolicy(0, 0, 0));    // never retry automatically
    }

    @Override
    public Result loadDataFromNetwork() throws Exception {
        lastInstance = this;

        final long startTime = System.currentTimeMillis();

        wakeLock.acquire();
        wifiLock.acquire();

        try {
            LogUtils.clearLog();

            // Prepare the MCC and MNC code filters.
            final String mccCodes = Settings.with(context).mccFilters();
            final String mncCodes = Settings.with(context).mncFilters();

            if (makeFilterArray(mccCodes, mccFilter)) {
                logInfo(context.getString(R.string.log_MCC_FILTER) + " " + mccCodes);
            } else {
                logInfo(context.getString(R.string.log_MCC_WORLD));
            }

            if (makeFilterArray(mncCodes, mncFilter)) {
                logInfo(context.getString(R.string.log_MNC_FILTER) + " " + mncCodes);
            } else {
                logInfo(context.getString(R.string.log_MNC_WORLD));
            }

            try {
                databaseCreator = DatabaseCreator.withTempFile().open().createTable();

                if (Settings.with(context).useOpenCellId() && !isCancelled()) {
                    logInfo(context.getString(R.string.log_GETTING_OCID));
                    getData(String.format(Locale.US, Config.OCI_URL_FMT, Settings.with(context).openCellIdApiKey()));
                }

                if (Settings.with(context).useMozillaLocationService() && !isCancelled()) {
                    logInfo(context.getString(R.string.log_GETTING_MOZ));
                    SimpleDateFormat dateFormatGmt = new SimpleDateFormat("yyyy-MM-dd", Locale.US);
                    // Mozilla publishes new CSV files at a bit after the beginning of
                    // a new day in GMT time. Get the time for a place a couple hours
                    // west of Greenwich to allow time for the data to be posted.
                    dateFormatGmt.setTimeZone(TimeZone.getTimeZone("GMT-03"));
                    getData(String.format(Locale.US, Config.MLS_URL_FMT, dateFormatGmt.format(new Date())));
                }

                if (!isCancelled()) {
                    publishProgress(100, context.getString(R.string.log_INDICIES));

                    databaseCreator
                            .createIndex()
                            .close()
                            .removeJournal()
                            .replace(Config.DB_NEW_FILE);

                } else {
                    databaseCreator.close().delete();
                }
            } catch (Exception ex) {
                logError(ex.getMessage());

                // On any failure, remove the result file.
                if(databaseCreator != null) {
                    databaseCreator.close().delete();
                }

                throw ex;
            }
        } finally {
            wifiLock.release();
            wakeLock.release();

            final long exitTime = System.currentTimeMillis();
            final long execTime = exitTime - startTime;

            logInfo(context.getString(R.string.log_TOT_TIME) + " " + execTime + " " +  context.getString(R.string.log_MILLISEC));

            logInfo(context.getString(R.string.log_FINISHED));
        }

        return null;
    }

    /**
     * Turn comma-separated string with MCC/MNC codes into a boolean array for filtering.
     * @param codesStr Empty string or a string of comma-separated numbers.
     * @param outputArray 1000-element boolean array filled with false values. Elements with
     *                    indices corresponding to codes found in {@code codesStr} will be
     *                    changed to true.
     * @return True if the string contained at least one valid (0-999) code, false otherwise.
     */
    private boolean makeFilterArray(String codesStr, boolean[] outputArray) {
        if (codesStr.isEmpty()) {
            Arrays.fill(outputArray, Boolean.TRUE);
            return false;
        } else {
            int enabledCount = 0, code;
            for (String codeStr : codesStr.split(",")) {
                try {
                    code = Integer.parseInt(codeStr);
                } catch (NumberFormatException e) {
                    continue;
                }
                if (code >= 0 && code <= 999) {
                    outputArray[code] = true;
                    enabledCount++;
                }
            }
            if (enabledCount == 0) {
                // The string contained only number(s) larger than
                // 999, only commas or some other surprise.
                Arrays.fill(outputArray, Boolean.TRUE);
                return false;
            }
        }

        return true;
    }

    private void getData(String url) throws Exception {
        try {
            long maxLength;
            int totalRecords = 0;
            int insertedRecords = 0;

            long entryTime = System.currentTimeMillis();

            logInfo(context.getString(R.string.log_URL) + " " + url);

            HttpURLConnection c;
            URL u = new URL(url);

            if (u.getProtocol().equals("https")) {
                c = (HttpsURLConnection) u.openConnection();
            } else {
                c = (HttpURLConnection) u.openConnection();
            }
            c.setRequestMethod("GET");
            c.connect();

            // Looks like .gz is about a 4 to 1 compression ratio
            logInfo(context.getString(R.string.log_CONT_LENGTH) + " " + c.getContentLength());
            maxLength = c.getContentLength() * 4;

            CsvParser cvs = new CsvParser(
                    new BufferedInputStream(
                            new GZIPInputStream(
                                    new BufferedInputStream(
                                            c.getInputStream()))));

            // CSV Field    ==> Database Field
            // radio        ==>
            // mcc          ==> mcc
            // net          ==> mnc
            // area         ==> lac
            // cell         ==> cid
            // unit         ==>
            // lon          ==> longitude
            // lat          ==> latitude
            // range        ==> accuracy
            // samples      ==> samples
            // changeable   ==>
            // created      ==>
            // updated      ==>
            // averageSignal==>
            List headers = cvs.parseLine();
            int mccIndex = headers.indexOf("mcc");
            int mncIndex = headers.indexOf("net");
            int lacIndex = headers.indexOf("area");
            int cidIndex = headers.indexOf("cell");
            int lonIndex = headers.indexOf("lon");
            int latIndex = headers.indexOf("lat");
            int accIndex = headers.indexOf("range");
            int smpIndex = headers.indexOf("samples");

            databaseCreator.beginTransaction();

            List<String> rec;
            String RecsReadStr = context.getString(R.string.log_REC_READ);
            String RecsInsertedStr = context.getString(R.string.log_REC_INSERTED);

            while (((rec = cvs.parseLine()) != null) &&
                    (rec.size() > 8) &&
                    (!isCancelled())) {

                totalRecords++;

                int percentComplete = (int) ((100l * cvs.bytesRead()) / maxLength);
                if ((totalRecords % 1000) == 0) {
                    String statusText = RecsReadStr + " " + Integer.toString(totalRecords) +
                            ", " + RecsInsertedStr + " " + Integer.toString(insertedRecords);

                    publishProgress(percentComplete, statusText);
                }

                int mcc = Integer.parseInt(rec.get(mccIndex));
                int mnc = Integer.parseInt(rec.get(mncIndex));

                if ((mcc >= 0) && (mcc <= 999) && mccFilter[mcc] &&
                        (mnc >= 0) && (mnc <= 999) && mncFilter[mnc]) {

                    // Keep transaction size limited
                    if ((insertedRecords % TRANSACTION_SIZE_LIMIT) == 0) {
                        databaseCreator
                                .commitTransaction()
                                .beginTransaction();
                    }

                    databaseCreator.insert(
                            mcc,
                            rec.get(mncIndex),
                            rec.get(lacIndex),
                            rec.get(cidIndex),
                            rec.get(lonIndex),
                            rec.get(latIndex),
                            rec.get(accIndex),
                            rec.get(smpIndex)
                    );

                    insertedRecords++;
                }
            }

            if (isCancelled()) {
                logWarn(context.getString(R.string.st_CANCELED));
            }

            databaseCreator.commitTransaction();

            logInfo(RecsReadStr +
                    " " + Integer.toString(totalRecords) +
                    ", " + RecsInsertedStr +
                    " " + Integer.toString(insertedRecords));

            long exitTime = System.currentTimeMillis();
            long execTime = exitTime - entryTime;

            float f = (Math.round((1000.0f * execTime) / Math.min(totalRecords, 1)) / 1000.0f);

            logInfo(context.getString(R.string.log_TOTAL_TIME) +
                    " " + execTime +
                    " " + context.getString(R.string.log_MILLISEC) +
                    " (" + f + " " + context.getString(R.string.log_MILLISEC_PER_REC) + ")");

        } catch (MalformedURLException e) {
            logError("getData('" + url + "') failed: " + e.getMessage());

            throw e;
        } catch (Exception e) {
            logError(e.getMessage());

            e.printStackTrace();

            throw e;
        }
    }

    private void publishProgress(int percentage, String message) {
        lastProgress = percentage;
        logProgress(percentage, message);
    }

    private void logInfo(String info) {
        logGeneral("info", info, false);

        if(DEBUG) {
            Log.i(TAG, info);
        }
    }

    private void logError(String error) {
        logGeneral("fail", error, false);

        if(DEBUG) {
            Log.e(TAG, error);
        }
    }

    private void logWarn(String warning) {
        logGeneral("warn", warning, false);

        if(DEBUG) {
            Log.w(TAG, warning);
        }
    }

    private void logProgress(int progress, String message) {
        logGeneral(String.format("%03d", progress) + "%", message, true);

        if(DEBUG) {
            Log.v(TAG, Integer.toString(progress) + "%  " + message);
        }
    }

    private void logGeneral(String tag, String message, boolean isProgress) {
        if(isProgress) {
            lastProgressMessage = '[' + tag + "]  " + message + "\n";
        } else {
            lastProgressMessage = null;

            logBuilder.append('[')
                    .append(tag)
                    .append("]  ")
                    .append(message)
                    .append('\n');
        }

        LogUtils.appendToLog(tag + ": " + message);

        publishProgress(lastProgress);
    }

    public String getLog() {
        String lastProgressMessage = this.lastProgressMessage;
        String log = this.logBuilder.toString();

        if(TextUtils.isEmpty(lastProgressMessage)) {
            return log;
        } else {
            return log + lastProgressMessage;
        }
    }
}