package dk.aau.cs.psylog.analysis.soundsleepanalysis;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.util.Log;
import android.util.Pair;

import java.text.ParseException;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedList;
import java.util.List;
import java.util.Queue;

import dk.aau.cs.psylog.module_lib.DBAccessContract;
import dk.aau.cs.psylog.module_lib.IScheduledTask;

public class SoundSleepAnalysis implements IScheduledTask{
    Uri soundSleepStateAnalysisUri = Uri.parse(DBAccessContract.DBACCESS_CONTENTPROVIDER + "SOUNDSLEEPANALYSIS_state");
    SimpleDateFormat dateFormat = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS");
    ContentResolver contentResolver;

    public SoundSleepAnalysis(Context context) {
        this.contentResolver = context.getContentResolver();
    }

    private List<SoundData> loadData() {
        Uri uri = Uri.parse(DBAccessContract.DBACCESS_CONTENTPROVIDER + "SOUND_amplitudes");
        Cursor cursor = contentResolver.query(uri, new String[]{"_id", "amplitude", "time"}, null, null, null);
        List<SoundData> returnList = new ArrayList<>();
        if ((getLastPosition() > 5 && cursor.moveToPosition(getLastPosition() - 5)) || cursor.moveToFirst()) {
            do {
                float amplitude = cursor.getFloat(cursor.getColumnIndex("amplitude"));
                String time = cursor.getString(cursor.getColumnIndex("time"));
                lastPos = cursor.getInt(cursor.getColumnIndex("_id"));
                returnList.add(new SoundData(amplitude, time));
            } while (cursor.moveToNext());
            cursor.close();
        }
        return returnList;
    }

    //Logistic function
    private float probabilityFunc(float t) {
        float k = 2.0f;
        float res = (float) (1.0 / (1.0 + Math.exp(-k * (t - 3.0))));
        if (res > 1.0f)
            return 1.0f;
        return res;
    }

    public void Analyse() {
        Queue<SoundData> previousDataQueue = new LinkedList<>();
        List<SoundData> data = loadData();
        List<Pair<String, Float>> resultMap = new ArrayList<>();

        if (data.size() > 5) {
            for (int i = 0; i < 5; i++)
                previousDataQueue.add(data.get(i));
            data = makeMovingAverage(data.subList(5, data.size() - 1));
        } else {
            return;
        }

        Date oldTime;
        try {
            oldTime = loadTimeString();
        } catch (Exception e) {
            try {
                oldTime = convertTimeString(data.get(0).time);
            } catch (NullPointerException el) {
                return;
            }
        }

        float probabilitySleeping = 0.0f;
        for (SoundData soundData : data) {
            Date newTime = convertTimeString(soundData.time);
            if (isStationary(soundData, previousDataQueue)) {
                float timeElapsed = (float) ((newTime.getTime() - oldTime.getTime()) / (60.0 * 60.0 * 1000.0));
                probabilitySleeping = probabilityFunc(timeElapsed);
            } else {
                probabilitySleeping = 0.0f;
                oldTime = newTime;
            }
            resultMap.add(new Pair<>(soundData.time, probabilitySleeping));

            previousDataQueue.remove();
            previousDataQueue.add(soundData);
        }
        updatePosition(oldTime, probabilitySleeping);
        writeToDB(resultMap);
    }

    private void writeToDB(List<Pair<String, Float>> pairs) {
        Uri soundSleepStateAnalysisUri = Uri.parse(DBAccessContract.DBACCESS_CONTENTPROVIDER + "SOUNDSLEEPANALYSIS_sleepcalc");
        for (Pair<String, Float> pair : pairs) {
            ContentValues values = new ContentValues();
            values.put("prob", pair.second);
            values.put("time", pair.first);
            contentResolver.insert(soundSleepStateAnalysisUri, values);
        }
    }

    private Date convertTimeString(String s) {
        Date convertedTime = new Date();
        try {
            convertedTime = dateFormat.parse(s);
        } catch (ParseException e) {
            e.printStackTrace();
        }
        return convertedTime;
    }

    private boolean isStationary(SoundData accelerationData, Queue<SoundData> previousDataQueue) {
        for (SoundData toConsider : previousDataQueue) {
            if (outOfThreshHold(accelerationData, toConsider))
                return false;
        }
        return true;
    }

    private boolean outOfThreshHold(SoundData acc1, SoundData acc2) {
        return Math.abs(acc1.amplitude - acc2.amplitude) > 3000.0f;
    }

    private List<SoundData> makeMovingAverage(List<SoundData> data) {
        if (data.size() == 0)
            return null;
        SoundData MAOld = data.get(0);
        float alpha = 0.1f;
        for (SoundData MAnew : data) {
            MAnew.amplitude = alpha * MAnew.amplitude + (1 - alpha) * MAOld.amplitude;
            MAOld = MAnew;
        }
        return data;
    }

    private Date loadTimeString() throws Exception {
        Uri uri = soundSleepStateAnalysisUri;
        Cursor cursor = contentResolver.query(uri, new String[]{"timeAmpl"}, null, null, null);
        if (cursor.moveToFirst()) {
            String res = cursor.getString(cursor.getColumnIndex("timeAmpl"));
            if (res == null || res == "") {
                throw new Exception("No Time located");
            }
            cursor.close();
            return convertTimeString(res);
        } else {
            cursor.close();
            throw new Exception("No Time located");
        }
    }

    private int lastPos = -1;

    private int getLastPosition() {
        Uri uri = soundSleepStateAnalysisUri;
        Cursor cursor = contentResolver.query(uri, new String[]{"positionAmpl"}, null, null, null);
        if (cursor.moveToFirst()) {
            int res = cursor.getInt(cursor.getColumnIndex("positionAmpl"));
            cursor.close();
            return res;
        } else {
            cursor.close();
            return 0;
        }
    }

    private void updatePosition(Date oldTime, float lastProb) {
        Uri uri = soundSleepStateAnalysisUri;
        ContentValues values = new ContentValues();
        values.put("positionAmpl", lastPos);

        values.put("timeAmpl", dateFormat.format(oldTime));
        values.put("probAmpl", lastProb);
        Cursor cursor = contentResolver.query(uri, new String[]{"_id", "positionAmpl", "timeAmpl"}, null, null, null);
        if (cursor.getCount() > 0) {
            contentResolver.update(uri, values, "1=1", null);
        } else {
            contentResolver.insert(uri, values);
        }
        cursor.close();
    }

    @Override
    public void doTask() {
        Log.i("AmplSleepAnalysis", "analysen startet");
        this.Analyse();
        Log.i("AmplSleepAnalysis", "analysen færdig");
    }

    @Override
    public void setParameters(Intent i) {

    }
}

