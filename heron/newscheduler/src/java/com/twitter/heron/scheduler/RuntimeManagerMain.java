package com.twitter.heron.scheduler;

import java.io.IOException;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;
import java.util.logging.Logger;

import com.twitter.heron.spi.common.ClusterConfig;
import com.twitter.heron.spi.common.ClusterDefaults;
import com.twitter.heron.spi.common.Config;
import com.twitter.heron.spi.common.Context;
import com.twitter.heron.spi.common.Keys;
import com.twitter.heron.spi.scheduler.IRuntimeManager;
import com.twitter.heron.spi.statemgr.IStateManager;
import com.twitter.heron.spi.statemgr.SchedulerStateManagerAdaptor;
import com.twitter.heron.spi.utils.NetworkUtils;

import org.apache.commons.cli.DefaultParser;
import org.apache.commons.cli.CommandLine;
import org.apache.commons.cli.CommandLineParser;
import org.apache.commons.cli.Options;
import org.apache.commons.cli.Option;
import org.apache.commons.cli.ParseException;
import org.apache.commons.cli.HelpFormatter;

public class RuntimeManagerMain {
  private static final Logger LOG = Logger.getLogger(RuntimeManagerMain.class.getName());

  // Print usage options
  private static void usage(Options options) {
    HelpFormatter formatter = new HelpFormatter();
    formatter.printHelp( "SchedulerMain", options );
  }

  // Construct all required command line options
  private static Options constructOptions() {
    Options options = new Options();

    Option cluster = Option.builder("c")
        .desc("Cluster name in which the topology needs to run on")
        .longOpt("cluster")
        .hasArgs()
        .argName("cluster")
        .required()
        .build();

    Option role = Option.builder("r")
        .desc("Role under which the topology needs to run")
        .longOpt("role")
        .hasArgs()
        .argName("role")
        .required()
        .build();

    Option environment = Option.builder("e")
        .desc("Environment under which the topology needs to run")
        .longOpt("environment")
        .hasArgs()
        .argName("environment")
        .required()
        .build();

    Option topologyName = Option.builder("n")
        .desc("Name of the topology")
        .longOpt("topology_name")
        .hasArgs()
        .argName("topology name")
        .required()
        .build();

    Option topologyName = Option.builder("n")
        .desc("Heron home directory")
        .longOpt("heron_home")
        .hasArgs()
        .argName("heron home dir")
        .required()
        .build();

  }

   // construct command line help options
  private static Options constructHelpOptions() {
    Options options = new Options();
    Option help = Option.builder("h")
        .desc("List all options and their description")
        .longOpt("help")
        .build();

    options.addOption(help);
    return options;
  }

  public static void main(String[] args)
      throws ClassNotFoundException, IllegalAccessException, InstantiationException, IOException {

    String cluster = args[0];
    String role = args[1];
    String environ = args[2];
    String heronHome = args[3];
    String configPath = args[4];
    String configOverrideEncoded = args[5];
    String topologyName = args[6];
    String sCommand = args[7];

    // Optional argument in the case of restart - TO DO convert into CLI
    String containerId = Integer.toString(-1);
    if (args.length == 9) {
      containerId = args[8];
    }

    IRuntimeManager.Command command = IRuntimeManager.Command.makeCommand(sCommand);

    // first load the defaults, then the config from files to override it
    Config.Builder defaultsConfig = Config.newBuilder()
        .putAll(ClusterDefaults.getDefaults())
        .putAll(ClusterConfig.loadConfig(heronHome, configPath));

    // add config parameters from the command line
    Config.Builder commandLineConfig = Config.newBuilder()
        .put(Keys.cluster(), cluster)
        .put(Keys.role(), role)
        .put(Keys.environ(), environ)
        .put(Keys.topologyContainerIdentifier(), containerId);

    Config.Builder topologyConfig = Config.newBuilder()
        .put(Keys.topologyName(), topologyName);

    // TODO (Karthik) override any parameters from the command line

    // build the final config by expanding all the variables
    Config config = Config.expand(
        Config.newBuilder()
            .putAll(defaultsConfig.build())
            .putAll(commandLineConfig.build())
            .putAll(topologyConfig.build())
            .build());

    LOG.info("Static config loaded successfully ");
    LOG.info(config.toString());

    // 1. Do prepare work
    // create an instance of state manager
    String statemgrClass = Context.stateManagerClass(config);
    IStateManager statemgr = (IStateManager) Class.forName(statemgrClass).newInstance();

    // create an instance of runtime manager
    String runtimeManagerClass = Context.runtimeManagerClass(config);
    IRuntimeManager runtimeManager = (IRuntimeManager) Class.forName(runtimeManagerClass).newInstance();

    boolean isSuccessful = false;

    // Put it in a try block so that we can always clean resources
    try {
      // initialize the statemgr
      statemgr.initialize(config);

      boolean isValid = validateRuntimeManage(statemgr, topologyName);

      // 2. Try to manage topology if valid
      if (isValid) {
        // invoke the appropriate command to manage the topology
        LOG.log(Level.INFO, "Topology: {0} to be {1}ed", new Object[]{topologyName, command});

        isSuccessful = manageTopology(config, command, statemgr, runtimeManager);
      }
    } finally {
      // 3. Do generic cleaning
      // close the state manager
      statemgr.close();
      // close the runtime manager
      runtimeManager.close();

      // 4. Do post work basing on the result
    }

    // Log the result and exit
    if (!isSuccessful) {
      LOG.log(Level.SEVERE, "Failed to {0} topology {1}", new Object[]{command, topologyName});

      System.exit(1);
    } else {
      LOG.log(Level.SEVERE, "Topology {0} {1} successfully", new Object[]{topologyName, command});

      System.exit(0);
    }
  }


  public static boolean validateRuntimeManage(IStateManager statemgr, String topologyName) {
    // Check whether the topology has already been running
    Boolean isTopologyRunning =
        NetworkUtils.awaitResult(statemgr.isTopologyRunning(topologyName), 5, TimeUnit.SECONDS);

    if (isTopologyRunning == null || isTopologyRunning.equals(Boolean.FALSE)) {
      LOG.severe("No such topology exists");
      return false;
    }

    return true;
  }

  public static boolean manageTopology(
      Config config, IRuntimeManager.Command command,
      IStateManager statemgr, IRuntimeManager runtimeManager)
      throws ClassNotFoundException, IllegalAccessException, InstantiationException, IOException {
    // build the runtime config
    Config runtime = Config.newBuilder()
        .put(Keys.topologyName(), Context.topologyName(config))
        .put(Keys.schedulerStateManagerAdaptor(), new SchedulerStateManagerAdaptor(statemgr))
        .put(Keys.runtimeManagerClassInstance(), runtimeManager)
        .build();

    // create an instance of the runner class
    RuntimeManagerRunner runtimeManagerRunner =
        new RuntimeManagerRunner(config, runtime, command);

    // invoke the appropriate handlers based on command
    boolean ret = runtimeManagerRunner.call();

    return ret;
  }
}
