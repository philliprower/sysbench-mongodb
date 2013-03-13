//import com.mongodb.Mongo;
import com.mongodb.MongoClient;
import com.mongodb.MongoClientOptions;
import com.mongodb.DB;
import com.mongodb.DBCollection;
import com.mongodb.DBCursor;
import com.mongodb.BasicDBObject;
import com.mongodb.DBObject;
import com.mongodb.DBCursor;
import com.mongodb.ServerAddress;
import com.mongodb.WriteConcern;
import com.mongodb.CommandResult;
import com.mongodb.AggregationOutput;
import com.mongodb.WriteResult;

import java.util.Arrays;
import java.util.ArrayList;
import java.util.Date;
import java.util.Properties;
import java.util.List;
import java.io.BufferedWriter;
import java.io.FileWriter;
import java.io.File;
import java.io.Writer;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

public class jmongosysbenchexecute {
    public static AtomicLong globalInserts = new AtomicLong(0);
    public static AtomicLong globalDeletes = new AtomicLong(0);
    public static AtomicLong globalUpdates = new AtomicLong(0);
    public static AtomicLong globalPointQueries = new AtomicLong(0);
    public static AtomicLong globalRangeQueries = new AtomicLong(0);
    public static AtomicLong globalSysbenchTransactions = new AtomicLong(0);
    public static AtomicLong globalWriterThreads = new AtomicLong(0);
    
    public static Writer writer = null;
    public static boolean outputHeader = true;

    public static int numCollections;
    public static String dbName;
    public static int writerThreads;
    public static Integer numMaxInserts;
    public static long secondsPerFeedback;
    public static String logFileName;
    public static String indexTechnology;
    public static String readOnly;
    public static int runSeconds;
    
    public static int oltpRangeSize;
    public static int oltpPointSelects;
    public static int oltpSimpleRanges;
    public static int oltpSumRanges;
    public static int oltpOrderRanges;
    public static int oltpDistinctRanges;
    public static int oltpIndexUpdates;
    public static int oltpNonIndexUpdates;
    
    public static int allDone = 0;
    
    public jmongosysbenchexecute() {
    }

    public static void main (String[] args) throws Exception {
        if (args.length != 17) {
            logMe("*** ERROR : CONFIGURATION ISSUE ***");
            logMe("jsysbenchexecute [number of collections] [database name] [number of writer threads] [documents per collection] [seconds feedback] "+
                                   "[log file name] [technology = mongo|tokumon] [read only Y/N] [runtime (seconds)] [range size] [point selects] "+
                                   "[simple ranges] [sum ranges] [order ranges] [distinct ranges] [index updates] [non index updates]");
            System.exit(1);
        }
        
        numCollections = Integer.valueOf(args[0]);
        dbName = args[1];
        writerThreads = Integer.valueOf(args[2]);
        numMaxInserts = Integer.valueOf(args[3]);
        secondsPerFeedback = Long.valueOf(args[4]);
        logFileName = args[5];
        indexTechnology = args[6];
        readOnly = args[7];
        runSeconds = Integer.valueOf(args[8]);
        oltpRangeSize = Integer.valueOf(args[9]);
        oltpPointSelects = Integer.valueOf(args[10]);
        oltpSimpleRanges = Integer.valueOf(args[11]);
        oltpSumRanges = Integer.valueOf(args[12]);
        oltpOrderRanges = Integer.valueOf(args[13]);
        oltpDistinctRanges = Integer.valueOf(args[14]);
        oltpIndexUpdates = Integer.valueOf(args[15]);
        oltpNonIndexUpdates = Integer.valueOf(args[16]);
    
        logMe("Application Parameters");
        logMe("-------------------------------------------------------------------------------------------------");
        logMe("  collections              = %d",numCollections);
        logMe("  database name            = %s",dbName);
        logMe("  writer threads           = %d",writerThreads);
        logMe("  documents per collection = %,d",numMaxInserts);
        logMe("  feedback seconds         = %,d",secondsPerFeedback);
        logMe("  log file                 = %s",logFileName);
        logMe("  index technology         = %s",indexTechnology);
        logMe("  read only                = %s",readOnly);
        logMe("  run seconds              = %d",runSeconds);
        logMe("  oltp range size          = %d",oltpRangeSize);
        logMe("  oltp point selects       = %d",oltpPointSelects);
        logMe("  oltp simple ranges       = %d",oltpSimpleRanges);
        logMe("  oltp sum ranges          = %d",oltpSumRanges);
        logMe("  oltp order ranges        = %d",oltpOrderRanges);
        logMe("  oltp distinct ranges     = %d",oltpDistinctRanges);
        logMe("  oltp index updates       = %d",oltpIndexUpdates);
        logMe("  oltp non index updates   = %d",oltpNonIndexUpdates);
        logMe("-------------------------------------------------------------------------------------------------");

        try {
            writer = new BufferedWriter(new FileWriter(new File(logFileName)));
        } catch (IOException e) {
            e.printStackTrace();
        }

        if ((!indexTechnology.toLowerCase().equals("tokumon")) && (!indexTechnology.toLowerCase().equals("mongo"))) {
            // unknown index technology, abort
            logMe(" *** Unknown Indexing Technology %s, shutting down",indexTechnology);
            System.exit(1);
        }

        MongoClientOptions clientOptions = new MongoClientOptions.Builder().connectionsPerHost(2048).writeConcern(WriteConcern.FSYNC_SAFE).build();
        //clientOptions.connectionsPerHost = 2048;
        //m.setWriteConcern(WriteConcern.NORMAL);
        MongoClient m = new MongoClient("localhost", clientOptions);
        
        logMe("mongoOptions | " + m.getMongoOptions().toString());
        logMe("mongoWriteConcern | " + m.getWriteConcern().toString());
        
        DB db = m.getDB(dbName);
        
        jmongosysbenchexecute t = new jmongosysbenchexecute();

        Thread[] tWriterThreads = new Thread[writerThreads];
        
        for (int i=0; i<writerThreads; i++) {
            tWriterThreads[i] = new Thread(t.new MyWriter(writerThreads, i, numMaxInserts, db, numCollections));
            tWriterThreads[i].start();
        }
        
        Thread reporterThread = new Thread(t.new MyReporter());
        reporterThread.start();
        reporterThread.join();

        // wait for writer threads to terminate
        for (int i=0; i<writerThreads; i++) {
            if (tWriterThreads[i].isAlive())
                tWriterThreads[i].join();
        }
        
        try {
            if (writer != null) {
                writer.close();
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
        
        m.close();
        
        logMe("Done!");
    }
    
    class MyWriter implements Runnable {
        int threadCount; 
        int threadNumber; 
        int numTables;
        int numMaxInserts;
        int numCollections;
        DB db;
        
        long numInserts = 0;
        long numDeletes = 0;
        long numUpdates = 0;
        long numPointQueries = 0;
        long numRangeQueries = 0;
        
        java.util.Random rand;
        
        MyWriter(int threadCount, int threadNumber, int numMaxInserts, DB db, int numCollections) {
            this.threadCount = threadCount;
            this.threadNumber = threadNumber;
            this.numMaxInserts = numMaxInserts;
            this.db = db;
            this.numCollections = numCollections;
            rand = new java.util.Random((long) threadNumber);
        }
        public void run() {
            logMe("Writer thread %d : started",threadNumber);
            globalWriterThreads.incrementAndGet();
            
            while (allDone == 0) {
                String collectionName = "sbtest" + Integer.toString(rand.nextInt(numCollections)+1);
                DBCollection coll = db.getCollection(collectionName);
                
                for (int i=1; i <= oltpPointSelects; i++) {
                    //for i=1, oltp_point_selects do
                    //   rs = db_query("SELECT c FROM ".. table_name .." WHERE id=" .. sb_rand(1, oltp_table_size))
                    //end
                    
                    // db.sbtest8.find({_id: 554312}, {c: 1, _id: 0})
                    
                    int startId = rand.nextInt(numMaxInserts)+1;

                    BasicDBObject query = new BasicDBObject("_id", startId);
                    BasicDBObject columns = new BasicDBObject("c", 1).append("_id", 0);
                    
                    DBObject myDoc = coll.findOne(query, columns);
                    //System.out.println(myDoc);
                    
                    globalPointQueries.incrementAndGet();
                }
                
                for (int i=1; i <= oltpSimpleRanges; i++) {
                    //for i=1, oltp_simple_ranges do
                    //   range_start = sb_rand(1, oltp_table_size)
                    //   rs = db_query("SELECT c FROM ".. table_name .." WHERE id BETWEEN " .. range_start .. " AND " .. range_start .. "+" .. oltp_range_size - 1)
                    //end
                   
                    //db.sbtest8.find({_id: {$gte: 5523412, $lte: 5523512}}, {c: 1, _id: 0})
                   
                    int startId = rand.nextInt(numMaxInserts)+1;
                    int endId = startId + oltpRangeSize - 1;
                   
                    BasicDBObject query = new BasicDBObject("_id", new BasicDBObject("$gte", startId).append("$lte", endId));
                    BasicDBObject columns = new BasicDBObject("c", 1).append("_id", 0);
                    DBCursor cursor = coll.find(query, columns);
                    try {
                        while(cursor.hasNext()) {
                            cursor.next();
                            //System.out.println(cursor.next());
                        }
                    } finally {
                        cursor.close();
                    }
                    
                    globalRangeQueries.incrementAndGet();
                }

                for (int i=1; i <= oltpSumRanges; i++) {
                    //for i=1, oltp_sum_ranges do
                    //   range_start = sb_rand(1, oltp_table_size)
                    //   rs = db_query("SELECT SUM(K) FROM ".. table_name .." WHERE id BETWEEN " .. range_start .. " AND " .. range_start .. "+" .. oltp_range_size - 1)
                    //end
                   
                    //db.sbtest8.aggregate([ {$match: {_id: {$gt: 5523412, $lt: 5523512}}}, { $group: { _id: null, total: { $sum: "$k"}} } ])   

                    int startId = rand.nextInt(numMaxInserts)+1;
                    int endId = startId + oltpRangeSize - 1;

                    // create our pipeline operations, first with the $match
                    DBObject match = new BasicDBObject("$match", new BasicDBObject("_id", new BasicDBObject("$gte", startId).append("$lte", endId)));
                    
                    // build the $projection operation
                    DBObject fields = new BasicDBObject("k", 1);
                    fields.put("_id", 0);
                    DBObject project = new BasicDBObject("$project", fields );
                    
                    // Now the $group operation
                    DBObject groupFields = new BasicDBObject( "_id", null);
                    groupFields.put("average", new BasicDBObject( "$sum", "$k"));
                    DBObject group = new BasicDBObject("$group", groupFields);
                    
                    // run aggregation
                    AggregationOutput output = coll.aggregate( match, project, group );
                    
                    //System.out.println(output.getCommandResult());

                    globalRangeQueries.incrementAndGet();
                }
               
                for (int i=1; i <= oltpOrderRanges; i++) {
                    //for i=1, oltp_order_ranges do
                    //   range_start = sb_rand(1, oltp_table_size)
                    //   rs = db_query("SELECT c FROM ".. table_name .." WHERE id BETWEEN " .. range_start .. " AND " .. range_start .. "+" .. oltp_range_size - 1 .. " ORDER BY c")
                    //end
                
                    //db.sbtest8.find({_id: {$gte: 5523412, $lte: 5523512}}, {c: 1, _id: 0}).sort({c: 1})
                    
                    int startId = rand.nextInt(numMaxInserts)+1;
                    int endId = startId + oltpRangeSize - 1;
                   
                    BasicDBObject query = new BasicDBObject("_id", new BasicDBObject("$gte", startId).append("$lte", endId));
                    BasicDBObject columns = new BasicDBObject("c", 1).append("_id", 0);
                    DBCursor cursor = coll.find(query, columns).sort(new BasicDBObject("c",1));
                    try {
                        while(cursor.hasNext()) {
                            cursor.next();
                            //System.out.println(cursor.next());
                        }
                    } finally {
                        cursor.close();
                    }
                    
                    globalRangeQueries.incrementAndGet();
                }
            
                for (int i=1; i <= oltpDistinctRanges; i++) {
                    //for i=1, oltp_distinct_ranges do
                    //   range_start = sb_rand(1, oltp_table_size)
                    //   rs = db_query("SELECT DISTINCT c FROM ".. table_name .." WHERE id BETWEEN " .. range_start .. " AND " .. range_start .. "+" .. oltp_range_size - 1 .. " ORDER BY c")
                    //end
                   
                    //db.sbtest8.distinct("c",{_id: {$gt: 5523412, $lt: 5523512}}).sort()
                    
                    int startId = rand.nextInt(numMaxInserts)+1;
                    int endId = startId + oltpRangeSize - 1;
                   
                    BasicDBObject query = new BasicDBObject("_id", new BasicDBObject("$gte", startId).append("$lte", endId));
                    BasicDBObject columns = new BasicDBObject("c", 1).append("_id", 0);
                    List lstDistinct = coll.distinct("c", query);
                    //System.out.println(lstDistinct.toString());
                    
                    globalRangeQueries.incrementAndGet();
                }
                
            
                if (readOnly.toLowerCase().equals("n")) {
                    for (int i=1; i <= oltpIndexUpdates; i++) {
                        //for i=1, oltp_index_updates do
                        //   rs = db_query("UPDATE " .. table_name .. " SET k=k+1 WHERE id=" .. sb_rand(1, oltp_table_size))
                        //end

                        //db.sbtest8.update({_id: 5523412}, {$inc: {k: 1}}, false, false)
                        
                        int startId = rand.nextInt(numMaxInserts)+1;
                        
                        WriteResult wrUpdate = coll.update(new BasicDBObject("_id", startId), new BasicDBObject("$inc", new BasicDBObject("k",1)), false, false);

                        //System.out.println(wrUpdate.toString());
                    }

                    for (int i=1; i <= oltpNonIndexUpdates; i++) {
                        //for i=1, oltp_non_index_updates do
                        //   c_val = sb_rand_str("###########-###########-###########-###########-###########-###########-###########-###########-###########-###########")
                        //   query = "UPDATE " .. table_name .. " SET c='" .. c_val .. "' WHERE id=" .. sb_rand(1, oltp_table_size)
                        //   rs = db_query(query)
                        //   if rs then
                        //     print(query)
                        //   end
                        //end

                        //db.sbtest8.update({_id: 5523412}, {$set: {c: "hello there"}}, false, false)
                        
                        int startId = rand.nextInt(numMaxInserts)+1;

                        String cVal = sysbenchString(rand, "###########-###########-###########-###########-###########-###########-###########-###########-###########-###########");

                        WriteResult wrUpdate = coll.update(new BasicDBObject("_id", startId), new BasicDBObject("$set", new BasicDBObject("c",cVal)), false, false);
                        
                        //System.out.println(wrUpdate.toString());
                    }
                    
                    
                    //i = sb_rand(1, oltp_table_size)
                    //rs = db_query("DELETE FROM " .. table_name .. " WHERE id=" .. i)
                  
                    //db.sbtest8.remove({_id: 5523412})
                    
                    int startId = rand.nextInt(numMaxInserts)+1;
                    
                    WriteResult wrRemove = coll.remove(new BasicDBObject("_id", startId));


                    //c_val = sb_rand_str([[###########-###########-###########-###########-###########-###########-###########-###########-###########-###########]])
                    //pad_val = sb_rand_str([[###########-###########-###########-###########-###########]])
                    //rs = db_query("INSERT INTO " .. table_name ..  " (id, k, c, pad) VALUES " .. string.format("(%d, %d, '%s', '%s')",i, sb_rand(1, oltp_table_size) , c_val, pad_val))
            
                    BasicDBObject doc = new BasicDBObject();
                    doc.put("_id",startId);
                    doc.put("k",rand.nextInt(numMaxInserts)+1);
                    String cVal = sysbenchString(rand, "###########-###########-###########-###########-###########-###########-###########-###########-###########-###########");
                    doc.put("c",cVal);
                    String padVal = sysbenchString(rand, "###########-###########-###########-###########-###########");
                    doc.put("pad",padVal);
                    WriteResult wrInsert = coll.insert(doc);
                }
            
               globalSysbenchTransactions.incrementAndGet();
            }

            //} catch (Exception e) {
            //    logMe("Writer thread %d : EXCEPTION",threadNumber);
            //    e.printStackTrace();
            //}
            
            globalWriterThreads.decrementAndGet();
        }
    }
    
    
    public static String sysbenchString(java.util.Random rand, String thisMask) {
        String returnString = "";
        for (int i = 0, n = thisMask.length() ; i < n ; i++) { 
            char c = thisMask.charAt(i); 
            if (c == '#') {
                returnString += String.valueOf(rand.nextInt(10));
            } else if (c == '@') {
                returnString += (char) (rand.nextInt(26) + 'a');
            } else {
                returnString += c;
            }
        }
        return returnString;
    }


    // reporting thread, outputs information to console and file
    class MyReporter implements Runnable {
        public void run()
        {
            long t0 = System.currentTimeMillis();
            long lastInserts = 0;
            long thisInserts = 0;
            long lastDeletes = 0;
            long thisDeletes = 0;
            long lastUpdates = 0;
            long thisUpdates = 0;
            long lastPointQueries = 0;
            long thisPointQueries = 0;
            long lastRangeQueries = 0;
            long thisRangeQueries = 0;
            long lastSysbenchTransactions = 0;
            long thisSysbenchTransactions = 0;
            long lastMs = t0;
            long intervalNumber = 0;
            long nextFeedbackMillis = t0 + (1000 * secondsPerFeedback * (intervalNumber + 1));
            long runEndMillis = Long.MAX_VALUE;
            if (runSeconds > 0)
                runEndMillis = t0 + (1000 * runSeconds);
            
            while ((System.currentTimeMillis() < runEndMillis) && (thisInserts < numMaxInserts))
            {
                try {
                    Thread.sleep(100);
                } catch (Exception e) {
                    e.printStackTrace();
                }
                
                long now = System.currentTimeMillis();
                
                
//    public static AtomicLong globalDeletes = new AtomicLong(0);
//    public static AtomicLong globalUpdates = new AtomicLong(0);
//    public static AtomicLong globalPointQueries = new AtomicLong(0);
//    public static AtomicLong globalRangeQueries = new AtomicLong(0);

                
                thisInserts = globalInserts.get();
                thisSysbenchTransactions = globalSysbenchTransactions.get();
                
                if ((now > nextFeedbackMillis) && (secondsPerFeedback > 0))
                {
                    intervalNumber++;
                    nextFeedbackMillis = t0 + (1000 * secondsPerFeedback * (intervalNumber + 1));

                    long elapsed = now - t0;
                    long thisIntervalMs = now - lastMs;

                    long thisIntervalSysbenchTransactions = thisSysbenchTransactions - lastSysbenchTransactions;
                    double thisIntervalSysbenchTransactionsPerSecond = thisIntervalSysbenchTransactions/(double)thisIntervalMs*1000.0;
                    double thisSysbenchTransactionsPerSecond = thisSysbenchTransactions/(double)elapsed*1000.0;

                    long thisIntervalInserts = thisInserts - lastInserts;
                    double thisIntervalInsertsPerSecond = thisIntervalInserts/(double)thisIntervalMs*1000.0;
                    double thisInsertsPerSecond = thisInserts/(double)elapsed*1000.0;
                    
                    logMe("%,d seconds : cum tps=%,.2f : int tps=%,.2f : cum ips=%,.2f : int ips=%,.2f", elapsed / 1000l, thisSysbenchTransactionsPerSecond, thisIntervalSysbenchTransactionsPerSecond, thisInsertsPerSecond, thisIntervalInsertsPerSecond);
                    
                    try {
                        if (outputHeader)
                        {
                            writer.write("elap_secs\tcum_tps\tint_tps\tcum_ips\tint_ips\n");
                            outputHeader = false;
                        }
                            
                        String statusUpdate = "";
                        
                        statusUpdate = String.format("%d\t%.2f\t%.2f\t%.2f\t%.2f\n", elapsed / 1000l, thisSysbenchTransactionsPerSecond, thisIntervalSysbenchTransactionsPerSecond, thisInsertsPerSecond, thisIntervalInsertsPerSecond);
                            
                        writer.write(statusUpdate);
                        writer.flush();
                    } catch (IOException e) {
                        e.printStackTrace();
                    }

                    lastInserts = thisInserts;
                    lastSysbenchTransactions = thisSysbenchTransactions;

                    lastMs = now;
                }
            }
            
            // shutdown all the writers
            allDone = 1;
        }
    }


    public static void logMe(String format, Object... args) {
        System.out.println(Thread.currentThread() + String.format(format, args));
    }
}
