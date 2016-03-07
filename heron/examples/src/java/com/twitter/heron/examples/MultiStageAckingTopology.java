package com.twitter.heron.examples;

import java.util.Map;
import java.util.Random;

import backtype.storm.Config;
import backtype.storm.StormSubmitter;
import backtype.storm.metric.api.GlobalMetrics;
import backtype.storm.spout.SpoutOutputCollector;
import backtype.storm.task.OutputCollector;
import backtype.storm.task.TopologyContext;
import backtype.storm.topology.OutputFieldsDeclarer;
import backtype.storm.topology.TopologyBuilder;
import backtype.storm.topology.base.BaseRichBolt;
import backtype.storm.topology.base.BaseRichSpout;
import backtype.storm.tuple.Fields;
import backtype.storm.tuple.Tuple;
import backtype.storm.tuple.Values;
import backtype.storm.utils.Utils;

/**
 * This is three stage topology. Spout emits to bolt to bolt.
 */
public class MultiStageAckingTopology {
  public static class AckingTestWordSpout extends BaseRichSpout {
    SpoutOutputCollector _collector;
    String[] words;
    Random rand;

    public AckingTestWordSpout() {
    }

    public void open(Map conf, TopologyContext context, SpoutOutputCollector collector) {
      _collector = collector;
      words = new String[]{"nathan", "mike", "jackson", "golda", "bertels"};
      rand = new Random();
    }

    public void close() {
    }

    public void nextTuple() {
      // We explicitly slow down the spout to avoid the stream mgr to be the bottleneck
      Utils.sleep(1);
      final String word = words[rand.nextInt(words.length)];
      // To enable acking, we need to emit tuple with MessageId, which is an object
      _collector.emit(new Values(word), "MESSAGE_ID");
    }

    public void ack(Object msgId) {
    }

    public void fail(Object msgId) {
    }

    public void declareOutputFields(OutputFieldsDeclarer declarer) {
      declarer.declare(new Fields("word"));
    }
  }

  public static class ExclamationBolt extends BaseRichBolt {
    OutputCollector _collector;
    long nItems;
    long startTime;
    boolean emit;

    public ExclamationBolt(boolean emit) {
      this.emit = emit;
    }

    @Override
    public void prepare(Map conf, TopologyContext context, OutputCollector collector) {
      _collector = collector;
      nItems = 0;
      startTime = System.currentTimeMillis();
    }

    @Override
    public void execute(Tuple tuple) {
      // We need to ack a tuple when we consider it is done successfully
      // Or we could fail it by invoking _collector.fail(tuple)
      // If we do not do the ack or fail explicitly
      // After the MessageTimeout Seconds, which could be set in Config, the spout will fail this tuple
      ++nItems;
      if (nItems % 10000 == 0) {
        long latency = System.currentTimeMillis() - startTime;
        System.out.println("Done " + nItems + " in " + latency);
        GlobalMetrics.incr("selected_items");
      }
      if (emit) {
        _collector.emit(tuple, new Values(tuple.getString(0) + "!!!"));
      }
      _collector.ack(tuple);
    }

    @Override
    public void declareOutputFields(OutputFieldsDeclarer declarer) {
      if (emit) {
        declarer.declare(new Fields("word"));
      }
    }
  }

  public static void main(String[] args) throws Exception {
    if (args.length != 1) {
      throw new RuntimeException("Please specify the name of the topology");
    }
    TopologyBuilder builder = new TopologyBuilder();

    builder.setSpout("word", new AckingTestWordSpout(), 2);
    builder.setBolt("exclaim1", new ExclamationBolt(true), 2)
        .shuffleGrouping("word");
    builder.setBolt("exclaim2", new ExclamationBolt(false), 2)
        .shuffleGrouping("exclaim1");

    Config conf = new Config();
    conf.setDebug(true);
    // Put an arbitrary large number here if you don't want to slow the topology down
    conf.setMaxSpoutPending(1000 * 1000 * 1000);
    // To enable acking, we need to setEnableAcking true
    conf.setEnableAcking(true);
    conf.put(Config.TOPOLOGY_WORKER_CHILDOPTS, "-XX:+HeapDumpOnOutOfMemoryError");
    conf.setNumStmgrs(1);
    StormSubmitter.submitTopology(args[0], conf, builder.createTopology());
  }
}
