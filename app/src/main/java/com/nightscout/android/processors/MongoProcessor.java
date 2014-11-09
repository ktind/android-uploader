package com.nightscout.android.processors;

import android.content.Context;
import android.util.Log;

import com.mongodb.BasicDBObject;
import com.mongodb.DB;
import com.mongodb.DBCollection;
import com.mongodb.MongoClient;
import com.mongodb.MongoClientURI;
import com.mongodb.WriteConcern;
import com.nightscout.android.MainActivity;
import com.nightscout.android.dexcom.G4Download;
import com.nightscout.android.dexcom.Utils;
import com.nightscout.android.dexcom.records.CalRecord;
import com.nightscout.android.dexcom.records.EGVRecord;
import com.nightscout.android.dexcom.records.GlucoseDataSet;
import com.nightscout.android.dexcom.records.MeterRecord;
import com.nightscout.android.dexcom.records.SensorRecord;

import java.util.ArrayList;
import java.util.Date;

public class MongoProcessor extends AbstractProcessor {
    private final String TAG = MainActivity.class.getSimpleName();
    private String dbURI;
    private String collectionName;
    private String dsCollectionName;
    private boolean uploadSensorData;
    private boolean uploadCalData;

    public MongoProcessor(Context context, int deviceID){
        super(deviceID, context, "mongo");
        dbURI = sharedPref.getString("cloud_storage_mongodb_uri", null);
        collectionName = sharedPref.getString("cloud_storage_mongodb_collection", null);
        dsCollectionName = sharedPref.getString("cloud_storage_mongodb_device_status_collection", "devicestatus");
        uploadSensorData = sharedPref.getBoolean("uploadSensorData",false);
        uploadCalData = sharedPref.getBoolean("cloud_cal_data",false);
    }

    @Override
    public boolean process(G4Download download) {
        if (dbURI != null && collectionName != null) {
            try {
                // connect to db
                MongoClientURI uri = new MongoClientURI(dbURI.trim());
                MongoClient client = new MongoClient(uri);

                // get db
                DB db = client.getDB(uri.getDatabase());

                // get collection
                DBCollection dexcomData = db.getCollection(collectionName.trim());
                ArrayList<EGVRecord> egvRecords=filterRecords(download.getEGVRecords(),AbstractProcessor.LAST_EGV_REC_SHAREDPREF);
                ArrayList<SensorRecord> sensorRecords=filterRecords(download.getSensorRecords(),AbstractProcessor.LAST_SENSOR_REC_SHAREDPREF);
                ArrayList<MeterRecord> meterRecords=filterRecords(download.getMeterRecords(),AbstractProcessor.LAST_METER_REC_SHAREDPREF);
                ArrayList<CalRecord> calRecords=filterRecords(download.getCalRecords(),AbstractProcessor.LAST_CAL_REC_SHAREDPREF);
                GlucoseDataSet[] glucoseDataSets = Utils.mergeGlucoseDataRecords(egvRecords.toArray(new EGVRecord[egvRecords.size()]),sensorRecords.toArray(new SensorRecord[sensorRecords.size()]));
                Log.i(TAG, "The number of EGV records being sent to MongoDB is " + glucoseDataSets.length);
                for (GlucoseDataSet record : glucoseDataSets) {
                    // make db object
                    BasicDBObject testData = new BasicDBObject();
                    testData.put("device", "dexcom");
                    testData.put("date", record.getDisplayTime().getTime());
                    testData.put("dateString", record.getDisplayTime().toString());
                    testData.put("sgv", record.getBGValue());
                    testData.put("direction", record.getTrend().friendlyTrendName());
                    if (uploadSensorData) {
                        testData.put("filtered", record.getFiltered());
                        testData.put("unfilterd", record.getUnfiltered());
                        testData.put("rssi", record.getRssi());
                    }
                    dexcomData.update(testData, testData, true, false, WriteConcern.UNACKNOWLEDGED);
                }

                Log.i(TAG, "The number of MBG records being sent to MongoDB is " + meterRecords.size());
                for (MeterRecord meterRecord : meterRecords) {
                    // make db object
                    BasicDBObject testData = new BasicDBObject();
                    testData.put("device", "dexcom");
                    testData.put("type", "mbg");
                    testData.put("date", meterRecord.getDisplayTime().getTime());
                    testData.put("dateString", meterRecord.getDisplayTime().toString());
                    testData.put("mbg", meterRecord.getMeterBG());
                    dexcomData.update(testData, testData, true, false, WriteConcern.UNACKNOWLEDGED);
                }

                // TODO: might be best to merge with the the glucose data but will require time
                // analysis to match record with cal set, for now this will do
                if (uploadCalData) {
                    for (CalRecord calRecord : calRecords) {
                        // make db object
                        BasicDBObject testData = new BasicDBObject();
                        testData.put("device", "dexcom");
                        testData.put("date", calRecord.getDisplayTime().getTime());
                        testData.put("dateString", calRecord.getDisplayTime().toString());
                        testData.put("slope", calRecord.getSlope());
                        testData.put("intercept", calRecord.getIntercept());
                        testData.put("scale", calRecord.getScale());
                        dexcomData.update(testData, testData, true, false, WriteConcern.UNACKNOWLEDGED);
                    }
                }

                // TODO: quick port from original code, revisit before release
                DBCollection dsCollection = db.getCollection(dsCollectionName);
                BasicDBObject devicestatus = new BasicDBObject();
                devicestatus.put("uploaderBattery", MainActivity.batLevel);
                devicestatus.put("created_at", new Date());
                dsCollection.insert(devicestatus, WriteConcern.UNACKNOWLEDGED);
                client.close();
                return true;
            } catch (Exception e) {
                Log.e(TAG, "Unable to upload data to mongo", e);
            }
        }
        return true;
    }
}
