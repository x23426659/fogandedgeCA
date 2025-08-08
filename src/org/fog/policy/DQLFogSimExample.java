package org.fog.policy;
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
import org.fog.utils.Config;
import org.fog.utils.FogUtils;
import org.fog.utils.distribution.DeterministicDistribution;
import org.fog.application.selectivity.FractionalSelectivity;

import java.util.*;

public class DQLFogSimExample {

    static List<FogDevice> fogDevices = new ArrayList<>();
    static List<Sensor> sensors = new ArrayList<>();
    static List<Actuator> actuators = new ArrayList<>();
    static int numFogNodes = 3;
    static int numIoTDevices = 3;
    static Application application;
    static int userId = 0;

    public static void main(String[] args) {
        Log.printLine("Starting DQL Fog Simulation...");

        try {
            Log.printLine("Initialising...");
            CloudSim.init(1, Calendar.getInstance(), false);

            FogDevice cloud = createFogDevice("cloud", 44800, 40000, 100, 10000, 0, 0.01, 16 * 103, 16 * 83.25);
            cloud.setParentId(-1);
            fogDevices.add(cloud);

            for (int i = 0; i < numFogNodes; i++) {
                FogDevice fogDevice = createFogDevice("fog-" + (i + 1), 2800, 4000, 1000, 10000, 1, 0.0, 107.339, 83.4333);
                fogDevice.setParentId(cloud.getId());
                fogDevices.add(fogDevice);
            }

            application = createApplication("dql_app", userId);

            ModuleMapping moduleMapping = ModuleMapping.createModuleMapping();
            for (FogDevice fog : fogDevices) {
                if (!fog.getName().equals("cloud")) {
                    moduleMapping.addModuleToDevice("SensorModule", fog.getName());
                    moduleMapping.addModuleToDevice("ProcessingModule", fog.getName());
                    moduleMapping.addModuleToDevice("ActuatorModule", fog.getName());
                }
            }

            // Create sensors and actuators properly linked to fog devices and application
            for (int i = 0; i < numIoTDevices; i++) {
                FogDevice targetFog = fogDevices.get(1 + (i % numFogNodes));

                Sensor sensor = new Sensor("sensor-" + i, "DATA", userId, application.getAppId(), new DeterministicDistribution(5));
                sensor.setGatewayDeviceId(targetFog.getId());
                sensor.setLatency(1.0);
                sensor.setApp(application);
                sensor.setSensorName("SensorModule");  // Must match app module name

                Actuator actuator = new Actuator("actuator-" + i, userId, application.getAppId(), "ActuatorModule");
                actuator.setGatewayDeviceId(targetFog.getId());
                actuator.setLatency(1.0);
                actuator.setApp(application);

                sensors.add(sensor);
                actuators.add(actuator);
            }

            // Use ModulePlacementMapping for module placement based on your mapping
            ModulePlacementMapping modulePlacement = new ModulePlacementMapping(fogDevices, application, moduleMapping);

            // Controller takes fog devices, sensors, actuators, and module placement object
            Controller controller = new Controller("controller", fogDevices, sensors, actuators);

            // Submit application using the Controller
            controller.submitApplication(application, 0, modulePlacement);

            CloudSim.startSimulation();
            CloudSim.stopSimulation();

            Log.printLine("DQL Fog Simulation finished!");

        } catch (Exception e) {
            e.printStackTrace();
            Log.printLine("An error occurred.");
        }
    }

    private static FogDevice createFogDevice(String name, long mips, int ram, long upBw, long downBw, int level,
                                             double ratePerMips, double busyPower, double idlePower) {
        List<Pe> peList = new ArrayList<>();
        peList.add(new Pe(0, new PeProvisionerSimple(mips)));

        Host host = new Host(0, new RamProvisionerSimple(ram), new BwProvisionerSimple(10000), 10000,
                peList, new VmSchedulerTimeShared(peList));

        List<Host> hostList = new ArrayList<>();
        hostList.add(host);

        FogDeviceCharacteristics characteristics = new FogDeviceCharacteristics(
                "x86", "Linux", "Xen", host, 10.0, upBw, downBw, 0, ratePerMips);

        FogDevice fogdevice = null;
        try {
        	fogdevice = new FogDevice(name, characteristics, new VmAllocationPolicySimple(hostList),
                    new LinkedList<>(), 10, upBw, downBw, 0, ratePerMips);

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
