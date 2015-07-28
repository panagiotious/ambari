/**
* Licensed to the Apache Software Foundation (ASF) under one
* or more contributor license agreements.  See the NOTICE file
* distributed with this work for additional information
* regarding copyright ownership.  The ASF licenses this file
* to you under the Apache License, Version 2.0 (the
* "License"); you may not use this file except in compliance
* with the License.  You may obtain a copy of the License at
*
*     http://www.apache.org/licenses/LICENSE-2.0
*
* Unless required by applicable law or agreed to in writing, software
* distributed under the License is distributed on an "AS IS" BASIS,
* WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
* See the License for the specific language governing permissions and
* limitations under the License.
*/

package org.apache.ambari.server.state.cluster;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.ambari.server.AmbariException;
import org.apache.ambari.server.ServiceComponentNotFoundException;
import org.apache.ambari.server.ServiceNotFoundException;
import org.apache.ambari.server.api.services.AmbariMetaInfo;
import org.apache.ambari.server.orm.GuiceJpaInitializer;
import org.apache.ambari.server.orm.InMemoryDefaultTestModule;
import org.apache.ambari.server.orm.OrmTestHelper;
import org.apache.ambari.server.state.Cluster;
import org.apache.ambari.server.state.Clusters;
import org.apache.ambari.server.state.Host;
import org.apache.ambari.server.state.MaintenanceState;
import org.apache.ambari.server.state.RepositoryVersionState;
import org.apache.ambari.server.state.Service;
import org.apache.ambari.server.state.ServiceComponent;
import org.apache.ambari.server.state.ServiceComponentFactory;
import org.apache.ambari.server.state.ServiceComponentHost;
import org.apache.ambari.server.state.ServiceComponentHostFactory;
import org.apache.ambari.server.state.ServiceFactory;
import org.apache.ambari.server.state.StackId;
import org.apache.ambari.server.state.State;
import org.junit.After;
import org.junit.Assert;
import org.junit.Before;
import org.junit.Test;

import com.google.inject.Guice;
import com.google.inject.Inject;
import com.google.inject.Injector;
import com.google.inject.persist.PersistService;

/**
 * Tests AMBARI-9368 and AMBARI-9761 which produced a deadlock during read and
 * writes of some of the impl classes.
 */
public class ClusterDeadlockTest {
  private static final int NUMBER_OF_HOSTS = 100;
  private static final int NUMBER_OF_THREADS = 3;

  private final AtomicInteger hostNameCounter = new AtomicInteger(0);

  @Inject
  private Injector injector;

  @Inject
  private Clusters clusters;

  @Inject
  private ServiceFactory serviceFactory;

  @Inject
  private ServiceComponentFactory serviceComponentFactory;

  @Inject
  private ServiceComponentHostFactory serviceComponentHostFactory;

  @Inject
  private AmbariMetaInfo metaInfo;

  @Inject
  private OrmTestHelper helper;

  private StackId stackId = new StackId("HDP-0.1");

  /**
   * The cluster.
   */
  private Cluster cluster;

  /**
   *
   */
  private List<String> hostNames = new ArrayList<String>(NUMBER_OF_HOSTS);

  /**
   * Creates 100 hosts and adds them to the cluster.
   *
   * @throws Exception
   */
  @Before
  public void setup() throws Exception {
    injector = Guice.createInjector(new InMemoryDefaultTestModule());
    injector.getInstance(GuiceJpaInitializer.class);
    injector.injectMembers(this);
    clusters.addCluster("c1");
    cluster = clusters.getCluster("c1");
    cluster.setDesiredStackVersion(stackId);
    helper.getOrCreateRepositoryVersion(stackId.getStackName(), stackId.getStackVersion());
    cluster.createClusterVersion(stackId.getStackName(),
        stackId.getStackVersion(), "admin", RepositoryVersionState.UPGRADING);

    metaInfo.init();

    // 100 hosts
    for (int i = 0; i < NUMBER_OF_HOSTS; i++) {
      String hostName = "c64-" + i;
      hostNames.add(hostName);

      clusters.addHost(hostName);
      setOsFamily(clusters.getHost(hostName), "redhat", "6.4");
      clusters.getHost(hostName).persist();
      clusters.mapHostToCluster(hostName, "c1");
    }

    Service service = installService("HDFS");
    addServiceComponent(service, "NAMENODE");
    addServiceComponent(service, "DATANODE");
  }

  @After
  public void teardown() {
    injector.getInstance(PersistService.class).stop();
  }

  /**
   * Tests that concurrent impl serialization and impl writing doesn't cause a
   * deadlock.
   *
   * @throws Exception
   */
  @Test(timeout = 30000)
  public void testDeadlockBetweenImplementations() throws Exception {
    Service service = cluster.getService("HDFS");
    ServiceComponent nameNodeComponent = service.getServiceComponent("NAMENODE");
    ServiceComponent dataNodeComponent = service.getServiceComponent("DATANODE");

    ServiceComponentHost nameNodeSCH = createNewServiceComponentHost("HDFS",
        "NAMENODE", "c64-0");

    ServiceComponentHost dataNodeSCH = createNewServiceComponentHost("HDFS",
        "DATANODE", "c64-0");

    List<Thread> threads = new ArrayList<Thread>();
    for (int i = 0; i < NUMBER_OF_THREADS; i++) {
      DeadlockExerciserThread thread = new DeadlockExerciserThread();
      thread.setCluster(cluster);
      thread.setService(service);
      thread.setDataNodeComponent(dataNodeComponent);
      thread.setNameNodeComponent(nameNodeComponent);
      thread.setNameNodeSCH(nameNodeSCH);
      thread.setDataNodeSCH(dataNodeSCH);
      thread.start();
      threads.add(thread);
    }

    for (Thread thread : threads) {
      thread.join();
    }
  }

  /**
   * Tests that while serializing a service component, writes to that service
   * component do not cause a deadlock with the global cluster lock.
   *
   * @throws Exception
   */
  @Test(timeout = 35000)
  public void testAddingHostComponentsWhileReading() throws Exception {
    Service service = cluster.getService("HDFS");
    ServiceComponent nameNodeComponent = service.getServiceComponent("NAMENODE");
    ServiceComponent dataNodeComponent = service.getServiceComponent("DATANODE");

    List<Thread> threads = new ArrayList<Thread>();
    for (int i = 0; i < 5; i++) {
      ServiceComponentReaderWriterThread thread = new ServiceComponentReaderWriterThread();
      thread.setDataNodeComponent(dataNodeComponent);
      thread.setNameNodeComponent(nameNodeComponent);
      thread.start();
      threads.add(thread);
    }

    for (Thread thread : threads) {
      thread.join();
    }
  }

  /**
   * Tests that no deadlock exists while restarting components and reading from
   * the cluster.
   *
   * @throws Exception
   */
  @Test(timeout = 60000)
  public void testDeadlockWhileRestartingComponents() throws Exception {
    // for each host, install both components
    List<ServiceComponentHost> serviceComponentHosts = new ArrayList<ServiceComponentHost>();
    for (String hostName : hostNames) {
      serviceComponentHosts.add(createNewServiceComponentHost("HDFS",
          "NAMENODE", hostName));

      serviceComponentHosts.add(createNewServiceComponentHost("HDFS",
          "DATANODE", hostName));
    }

    // !!! needed to populate some maps; without this, the cluster report
    // won't do anything and this test will be worthless
    ((ClusterImpl) cluster).loadServiceHostComponents();

    List<Thread> threads = new ArrayList<Thread>();
    for (int i = 0; i < NUMBER_OF_THREADS; i++) {
      ClusterReaderThread clusterReaderThread = new ClusterReaderThread();
      ClusterWriterThread clusterWriterThread = new ClusterWriterThread();
      ServiceComponentRestartThread schWriterThread = new ServiceComponentRestartThread(
          serviceComponentHosts);

      threads.add(clusterReaderThread);
      threads.add(clusterWriterThread);
      threads.add(schWriterThread);

      clusterReaderThread.start();
      clusterWriterThread.start();
      schWriterThread.start();
    }

    for (Thread thread : threads) {
      thread.join();
    }
  }

  /**
   * The {@link ClusterReaderThread} reads from a cluster over and over again
   * with a slight pause.
   */
  private final class ClusterReaderThread extends Thread {

    /**
     * {@inheritDoc}
     */
    @Override
    public void run() {
      try {
        for (int i = 0; i < 1500; i++) {
          cluster.convertToResponse();
          Thread.sleep(10);
        }
      } catch (Exception exception) {
        throw new RuntimeException(exception);
      }
    }
  }

  /**
   * The {@link ClusterWriterThread} writes some information to the cluster
   * instance over and over again with a slight pause.
   */
  private final class ClusterWriterThread extends Thread {

    /**
     * {@inheritDoc}
     */
    @Override
    public void run() {
      try {
        for (int i = 0; i < 1500; i++) {
          cluster.setDesiredStackVersion(stackId);
          Thread.sleep(10);
        }
      } catch (Exception exception) {
        throw new RuntimeException(exception);
      }
    }
  }

  /**
   * The {@link ServiceComponentRestartThread} is used to constantly set SCH
   * restart values.
   */
  private final class ServiceComponentRestartThread extends Thread {
    private List<ServiceComponentHost> serviceComponentHosts;

    /**
     * Constructor.
     *
     * @param serviceComponentHosts
     */
    private ServiceComponentRestartThread(
        List<ServiceComponentHost> serviceComponentHosts) {
      this.serviceComponentHosts = serviceComponentHosts;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void run() {
      try {
        for (int i = 0; i < 1000; i++) {
          // about 30ms to go through all SCHs, no sleep needed
          for (ServiceComponentHost serviceComponentHost : serviceComponentHosts) {
            serviceComponentHost.setRestartRequired(true);
          }
        }
      } catch (Exception exception) {
        throw new RuntimeException(exception);
      }
    }
  }

  /**
   * The {@link ServiceComponentRestartThread} is used to continuously add
   * components to hosts while reading from those components.
   */
  private final class ServiceComponentReaderWriterThread extends Thread {
    private ServiceComponent nameNodeComponent;
    private ServiceComponent dataNodeComponent;

    /**
     * @param nameNodeComponent
     *          the nameNodeComponent to set
     */
    public void setNameNodeComponent(ServiceComponent nameNodeComponent) {
      this.nameNodeComponent = nameNodeComponent;
    }

    /**
     * @param dataNodeComponent
     *          the dataNodeComponent to set
     */
    public void setDataNodeComponent(ServiceComponent dataNodeComponent) {
      this.dataNodeComponent = dataNodeComponent;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void run() {
      try {
        for (int i = 0; i < 15; i++) {
          int hostNumeric = hostNameCounter.getAndIncrement();

          nameNodeComponent.convertToResponse();
          createNewServiceComponentHost("HDFS", "NAMENODE", "c64-"
              + hostNumeric);

          dataNodeComponent.convertToResponse();
          createNewServiceComponentHost("HDFS", "DATANODE", "c64-"
              + hostNumeric);

          Thread.sleep(10);
        }
      } catch (Exception exception) {
        throw new RuntimeException(exception);
      }
    }
  }

  /**
   * Tests AMBARI-9368 which produced a deadlock during read and writes of some
   * of the impl classes.
   */
  private static final class DeadlockExerciserThread extends Thread {
    private Cluster cluster;
    private Service service;
    private ServiceComponent nameNodeComponent;
    private ServiceComponent dataNodeComponent;
    private ServiceComponentHost nameNodeSCH;
    private ServiceComponentHost dataNodeSCH;

    /**
     * @param cluster
     *          the cluster to set
     */
    public void setCluster(Cluster cluster) {
      this.cluster = cluster;
    }

    /**
     * @param service
     *          the service to set
     */
    public void setService(Service service) {
      this.service = service;
    }

    /**
     * @param nameNodeComponent
     *          the nameNodeComponent to set
     */
    public void setNameNodeComponent(ServiceComponent nameNodeComponent) {
      this.nameNodeComponent = nameNodeComponent;
    }

    /**
     * @param dataNodeComponent
     *          the dataNodeComponent to set
     */
    public void setDataNodeComponent(ServiceComponent dataNodeComponent) {
      this.dataNodeComponent = dataNodeComponent;
    }

    /**
     * @param nameNodeSCH
     *          the nameNodeSCH to set
     */
    public void setNameNodeSCH(ServiceComponentHost nameNodeSCH) {
      this.nameNodeSCH = nameNodeSCH;
    }

    /**
     * @param dataNodeSCH
     *          the dataNodeSCH to set
     */
    public void setDataNodeSCH(ServiceComponentHost dataNodeSCH) {
      this.dataNodeSCH = dataNodeSCH;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void run() {
      try {
        for (int i = 0; i < 10; i++) {
          cluster.convertToResponse();
          service.convertToResponse();
          nameNodeComponent.convertToResponse();
          dataNodeComponent.convertToResponse();
          nameNodeSCH.convertToResponse();
          dataNodeSCH.convertToResponse();

          cluster.setProvisioningState(org.apache.ambari.server.state.State.INIT);
          service.setMaintenanceState(MaintenanceState.OFF);
          nameNodeComponent.setDesiredState(org.apache.ambari.server.state.State.STARTED);
          dataNodeComponent.setDesiredState(org.apache.ambari.server.state.State.INSTALLED);

          nameNodeSCH.setState(org.apache.ambari.server.state.State.STARTED);
          dataNodeSCH.setState(org.apache.ambari.server.state.State.INSTALLED);

          Thread.sleep(100);
        }
      } catch (Exception exception) {
        throw new RuntimeException(exception);
      }
    }
  }

  private void setOsFamily(Host host, String osFamily, String osVersion) {
    Map<String, String> hostAttributes = new HashMap<String, String>(2);
    hostAttributes.put("os_family", osFamily);
    hostAttributes.put("os_release_version", osVersion);
    host.setHostAttributes(hostAttributes);
  }

  private ServiceComponentHost createNewServiceComponentHost(String svc,
      String svcComponent, String hostName) throws AmbariException {
    Assert.assertNotNull(cluster.getConfigGroups());
    Service s = installService(svc);
    ServiceComponent sc = addServiceComponent(s, svcComponent);

    ServiceComponentHost sch = serviceComponentHostFactory.createNew(sc,
        hostName);

    sc.addServiceComponentHost(sch);
    sch.setDesiredState(State.INSTALLED);
    sch.setState(State.INSTALLED);
    sch.setDesiredStackVersion(stackId);
    sch.setStackVersion(stackId);

    sch.persist();
    return sch;
  }

  private Service installService(String serviceName) throws AmbariException {
    Service service = null;

    try {
      service = cluster.getService(serviceName);
    } catch (ServiceNotFoundException e) {
      service = serviceFactory.createNew(cluster, serviceName);
      cluster.addService(service);
      service.persist();
    }

    return service;
  }

  private ServiceComponent addServiceComponent(Service service,
      String componentName) throws AmbariException {
    ServiceComponent serviceComponent = null;
    try {
      serviceComponent = service.getServiceComponent(componentName);
    } catch (ServiceComponentNotFoundException e) {
      serviceComponent = serviceComponentFactory.createNew(service,
          componentName);
      service.addServiceComponent(serviceComponent);
      serviceComponent.setDesiredState(State.INSTALLED);
      serviceComponent.persist();
    }

    return serviceComponent;
  }
}