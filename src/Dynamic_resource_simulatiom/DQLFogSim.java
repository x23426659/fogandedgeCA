package Dynamic_resource_simulatiom;

import org.cloudbus.cloudsim.*;
import org.cloudbus.cloudsim.core.CloudSim;
import org.cloudbus.cloudsim.provisioners.BwProvisionerSimple;
import org.cloudbus.cloudsim.provisioners.PeProvisionerSimple;
import org.cloudbus.cloudsim.provisioners.RamProvisionerSimple;
import org.fog.application.AppEdge;
import org.fog.application.AppLoop;
import org.fog.application.Application;
import org.fog.entities.*;
import org.fog.placement.ModuleMapping;
import org.fog.placement.Controller;
import org.fog.placement.ModulePlacementMapping;
import org.fog.utils.distribution.DeterministicDistribution;
import org.fog.application.selectivity.FractionalSelectivity;
import org.cloudbus.cloudsim.VmAllocationPolicySimple;
import org.cloudbus.cloudsim.power.PowerHost;
import org.cloudbus.cloudsim.power.models.PowerModelLinear;

import java.util.*;

public class DQLFogSim {

    static List<FogDevice> fogDevices = new ArrayList<>();
    static List<Sensor> sensors = new ArrayList<>();
    static List<Actuator> actuators = new ArrayList<>();
    static int numFogNodes = 3;
    static int numIoTDevices = 3;
    static Application application;
    static int userId = 0;

    // For DQL simulation
    static Map<Integer, Double> qValues = new HashMap<>();
    static Random random = new Random(42);

    public static void main(String[] args) {
        Log.printLine("Starting DQL Fog Simulation...");

        try {
            CloudSim.init(1, Calendar.getInstance(), false);

            // ======== CREATE CLOUD AND FOG DEVICES ========
            FogDevice cloud = createFogDevice("cloud", 44800, 40000, 100, 10000, 0, 0.01, 1648.0, 1332.0);
            cloud.setParentId(-1);
            fogDevices.add(cloud);

            for (int i = 0; i < numFogNodes; i++) {
                FogDevice fogDevice = createFogDevice("fog-" + (i + 1), 5000, 4000, 1000, 10000, 1, 0.0, 107.339, 83.4333);
                fogDevice.setParentId(cloud.getId());
                fogDevices.add(fogDevice);
            }

            application = createApplication("dql_app", userId);

            // ======== DQL TRAINING ========
            for (FogDevice fog : fogDevices) {
                if (!fog.getName().equals("cloud")) {
                    qValues.put(fog.getId(), 0.0);
                }
            }
            runSimplifiedDQLPretraining();

            // ======== SENSOR & ACTUATOR CREATION WITH DQL ASSIGNMENT ========
            ModuleMapping moduleMapping = ModuleMapping.createModuleMapping();
            for (FogDevice fog : fogDevices) {
                if (!fog.getName().equals("cloud")) {
                    moduleMapping.addModuleToDevice("SensorModule", fog.getName());
                    moduleMapping.addModuleToDevice("ProcessingModule", fog.getName());
                    moduleMapping.addModuleToDevice("ActuatorModule", fog.getName());
                }
            }

            for (int i = 0; i < numIoTDevices; i++) {
                Sensor sensor = new Sensor("sensor-" + i, "DATA", userId, application.getAppId(), new DeterministicDistribution(1));
                int bestFog = selectBestFogByQ();
                sensor.setGatewayDeviceId(bestFog);
                sensor.setLatency(1.0);
                sensor.setApp(application);

                Actuator actuator = new Actuator("actuator-" + i, userId, application.getAppId(), "ActuatorModule");
                actuator.setGatewayDeviceId(bestFog);
                actuator.setLatency(1.0);
                actuator.setApp(application);

                sensors.add(sensor);
                actuators.add(actuator);
            }

            ModulePlacementMapping modulePlacement = new ModulePlacementMapping(fogDevices, application, moduleMapping);
            Controller controller = new Controller("controller", fogDevices, sensors, actuators);
            controller.submitApplication(application, 0, modulePlacement);

            // ======== RUN DQL SIMULATION ========
            Log.printLine("\n=== Running Simulation with DQL Mapping ===");
            CloudSim.startSimulation();
            CloudSim.stopSimulation();
            logMetrics("DQL");

            // ======== RUN ROUND-ROBIN BASELINE ========
            Log.printLine("\n=== Running Simulation with Round-Robin Mapping ===");
            resetSimulation();
            runRoundRobinSimulation();

        } catch (Exception e) {
            e.printStackTrace();
            Log.printLine("An error occurred.");
        }
    }

    // -------------------- NEW METHODS --------------------

    private static void runSimplifiedDQLPretraining() {
        int episodes = 200;
        double alpha = 0.1;
        double epsilon = 1.0;
        double epsilonMin = 0.05;
        double epsilonDecay = 0.98;

        for (int ep = 0; ep < episodes; ep++) {
            double taskSizeMB = 1 + random.nextDouble() * 9.0;
            double requiredCycles = 0.5 + random.nextDouble() * 2.0;

            int action;
            if (random.nextDouble() < epsilon) {
                List<Integer> fogIds = getFogNodeIdsExcludingCloud();
                action = fogIds.get(random.nextInt(fogIds.size()));
            } else {
                action = selectBestFogByQ();
            }

            double delay = estimateDelay(action, taskSizeMB, requiredCycles);
            double util = estimateUtilization(action);
            double reward = computeReward(delay, util);

            double oldQ = qValues.get(action);
            qValues.put(action, oldQ + alpha * (reward - oldQ));

            epsilon = Math.max(epsilonMin, epsilon * epsilonDecay);
        }

        Log.printLine("Learned Q-values: " + qValues);
    }

    private static void runRoundRobinSimulation() throws Exception {
        fogDevices.clear();
        sensors.clear();
        actuators.clear();

        FogDevice cloud = createFogDevice("cloud", 44800, 40000, 100, 10000, 0, 0.01, 1648.0, 1332.0);
        cloud.setParentId(-1);
        fogDevices.add(cloud);

        for (int i = 0; i < numFogNodes; i++) {
            FogDevice fogDevice = createFogDevice("fog-" + (i + 1), 5000, 4000, 1000, 10000, 1, 0.0, 107.339, 83.4333);
            fogDevice.setParentId(cloud.getId());
            fogDevices.add(fogDevice);
        }

        application = createApplication("rr_app", userId);
        ModuleMapping moduleMapping = ModuleMapping.createModuleMapping();

        for (FogDevice fog : fogDevices) {
            if (!fog.getName().equals("cloud")) {
                moduleMapping.addModuleToDevice("SensorModule", fog.getName());
                moduleMapping.addModuleToDevice("ProcessingModule", fog.getName());
                moduleMapping.addModuleToDevice("ActuatorModule", fog.getName());
            }
        }

        for (int i = 0; i < numIoTDevices; i++) {
            int fogIndex = (i % numFogNodes) + 1;
            Sensor sensor = new Sensor("sensor-rr-" + i, "DATA", userId, application.getAppId(), new DeterministicDistribution(1));
            sensor.setGatewayDeviceId(fogDevices.get(fogIndex).getId());
            sensor.setLatency(1.0);
            sensor.setApp(application);

            Actuator actuator = new Actuator("actuator-rr-" + i, userId, application.getAppId(), "ActuatorModule");
            actuator.setGatewayDeviceId(fogDevices.get(fogIndex).getId());
            actuator.setLatency(1.0);
            actuator.setApp(application);

            sensors.add(sensor);
            actuators.add(actuator);
        }

        ModulePlacementMapping modulePlacement = new ModulePlacementMapping(fogDevices, application, moduleMapping);
        Controller controller = new Controller("controllerRR", fogDevices, sensors, actuators);
        controller.submitApplication(application, 0, modulePlacement);

        CloudSim.startSimulation();
        CloudSim.stopSimulation();
        logMetrics("RoundRobin");
    }

    private static void logMetrics(String tag) {
        Log.printLine("\n--- Metrics for " + tag + " ---");
        for (FogDevice fd : fogDevices) {
            Log.printLine(fd.getName() + " CPU Utilization: " + fd.getHost().getUtilizationOfCpu());
        }
        Log.printLine("(Latency & task completion tracking can be added if you extend tuple processing hooks.)");
    }

    private static void resetSimulation() {
        CloudSim.init(1, Calendar.getInstance(), false);
    }

    // -------------------- HELPERS --------------------

    private static List<Integer> getFogNodeIdsExcludingCloud() {
        List<Integer> ids = new ArrayList<>();
        for (FogDevice fog : fogDevices) {
            if (!fog.getName().equals("cloud")) ids.add(fog.getId());
        }
        return ids;
    }

    private static int selectBestFogByQ() {
        return Collections.max(qValues.entrySet(), Map.Entry.comparingByValue()).getKey();
    }

    private static double estimateDelay(int fogId, double taskSizeMB, double requiredCycles) {
        FogDevice fog = getFogDeviceById(fogId);
        double upBw = fog.getUplinkBandwidth();

        double tTrans = (taskSizeMB * 8.0) / upBw * 1000.0;
        double hostMips = fog.getHost().getTotalMips();
        double tProc = (requiredCycles * 1000.0) / hostMips * 1000.0;
        double util = estimateUtilization(fogId);
        return tTrans + tProc + util * 50.0;
    }

    private static double estimateUtilization(int fogId) {
        return 0.2 + random.nextDouble() * 0.6;
    }

    private static double computeReward(double delay, double util) {
        return -(delay / 1000.0) - util;
    }

    private static FogDevice getFogDeviceById(int id) {
        for (FogDevice f : fogDevices) {
            if (f.getId() == id) return f;
        }
        return null;
    }

    private static FogDevice createFogDevice(String name, long mips, int ram, long upBw, long downBw, int level,
                                             double staticPowerPercent, double busyPower, double idlePower) {
        List<Pe> peList = new ArrayList<>();
        peList.add(new Pe(0, new PeProvisionerSimple(mips)));
        long hostStorage = 1000000;

        PowerHost host = new PowerHost(
                0,
                new RamProvisionerSimple(ram),
                new BwProvisionerSimple(10000),
                hostStorage,
                peList,
                new VmSchedulerTimeShared(peList),
                new PowerModelLinear(busyPower, staticPowerPercent)
        );

        List<Host> hostList = new ArrayList<>();
        hostList.add(host);

        FogDeviceCharacteristics characteristics = new FogDeviceCharacteristics(
                "x86", "Linux", "Xen", host, 10.0, upBw, downBw, 0, 0.01);

        FogDevice fogdevice = null;
        try {
            fogdevice = new FogDevice(name, characteristics, new VmAllocationPolicySimple(hostList),
                    new LinkedList<>(), hostStorage, upBw, downBw, 0, 0.01);
            fogdevice.setLevel(level);
            fogdevice.setParentId(-1);
        } catch (Exception e) {
            e.printStackTrace();
        }
        return fogdevice;
    }

    private static Application createApplication(String appId, int userId) {
        Application application = Application.createApplication(appId, userId);
        application.addAppModule("SensorModule", 10);
        application.addAppModule("ProcessingModule", 1000);
        application.addAppModule("ActuatorModule", 10);

        application.addAppEdge("SensorModule", "ProcessingModule", 3000, 500, "DATA", Tuple.UP, AppEdge.MODULE);
        application.addAppEdge("ProcessingModule", "ActuatorModule", 1000, 100, "RESULT", Tuple.DOWN, AppEdge.MODULE);
        application.addTupleMapping("ProcessingModule", "DATA", "RESULT", new FractionalSelectivity(1.0));

        final AppLoop loop = new AppLoop(Arrays.asList("SensorModule", "ProcessingModule", "ActuatorModule"));
        application.setLoops(Collections.singletonList(loop));

        return application;
    }
}
