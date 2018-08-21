/*
* Licensed to the Apache Software Foundation (ASF) under one
* or more contributor license agreements.  See the NOTICE file
* distributed with this work for additional information
* regarding copyright ownership.  The ASF licenses this file
* to you under the Apache License, Version 2.0 (the
* "License"); you may not use this file except in compliance
* with the License.  You may obtain a copy of the License at
*
*    http://www.apache.org/licenses/LICENSE-2.0
*
* Unless required by applicable law or agreed to in writing,
* software distributed under the License is distributed on an
* "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
* KIND, either express or implied.  See the License for the
* specific language governing permissions and limitations
* under the License.
*/
package org.apache.cassandra.config;

import java.io.IOException;
import java.lang.management.ManagementFactory;
import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.NetworkInterface;
import java.util.Enumeration;

import javax.management.InstanceNotFoundException;
import javax.management.MBeanRegistrationException;
import javax.management.MBeanServer;
import javax.management.MalformedObjectNameException;
import javax.management.ObjectName;

import org.junit.BeforeClass;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.apache.cassandra.OrderedJUnit4ClassRunner;
import org.apache.cassandra.SchemaLoader;
import org.apache.cassandra.db.Keyspace;
import org.apache.cassandra.exceptions.ConfigurationException;
import org.apache.cassandra.exceptions.InvalidRequestException;
import org.apache.cassandra.gms.Gossiper;
import org.apache.cassandra.schema.KeyspaceMetadata;
import org.apache.cassandra.schema.KeyspaceParams;
import org.apache.cassandra.service.MigrationManager;
import org.apache.cassandra.thrift.ThriftConversion;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.fail;

@RunWith(OrderedJUnit4ClassRunner.class)
public class DatabaseDescriptorTest
{
    @BeforeClass
    public static void setupDatabaseDescriptor()
    {
        DatabaseDescriptor.setDaemonInitialized();
    }

    @Test
    public void testCFMetaDataSerialization() throws ConfigurationException, InvalidRequestException
    {
        // test serialization of all defined test CFs.
        for (String keyspaceName : Schema.instance.getNonSystemKeyspaces())
        {
            for (CFMetaData cfm : Schema.instance.getTablesAndViews(keyspaceName))
            {
                CFMetaData cfmDupe = ThriftConversion.fromThrift(ThriftConversion.toThrift(cfm));
                assertNotNull(cfmDupe);
                assertEquals(cfm, cfmDupe);
            }
        }
    }

    @Test
    public void testKSMetaDataSerialization() throws ConfigurationException
    {
        for (String ks : Schema.instance.getNonSystemKeyspaces())
        {
            // Not testing round-trip on the KsDef via serDe() because maps
            KeyspaceMetadata ksm = Schema.instance.getKSMetaData(ks);
            KeyspaceMetadata ksmDupe = ThriftConversion.fromThrift(ThriftConversion.toThrift(ksm));
            assertNotNull(ksmDupe);
            assertEquals(ksm, ksmDupe);
        }
    }

    // this came as a result of CASSANDRA-995
    @Test
    public void testTransKsMigration() throws ConfigurationException, IOException
    {
        SchemaLoader.cleanupAndLeaveDirs();
        Schema.instance.loadFromDisk();
        assertEquals(0, Schema.instance.getNonSystemKeyspaces().size());

        Gossiper.instance.start((int)(System.currentTimeMillis() / 1000));
        Keyspace.setInitialized();

        try
        {
            // add a few.
            MigrationManager.announceNewKeyspace(KeyspaceMetadata.create("ks0", KeyspaceParams.simple(3)));
            MigrationManager.announceNewKeyspace(KeyspaceMetadata.create("ks1", KeyspaceParams.simple(3)));

            assertNotNull(Schema.instance.getKSMetaData("ks0"));
            assertNotNull(Schema.instance.getKSMetaData("ks1"));

            Schema.instance.clearKeyspaceMetadata(Schema.instance.getKSMetaData("ks0"));
            Schema.instance.clearKeyspaceMetadata(Schema.instance.getKSMetaData("ks1"));

            assertNull(Schema.instance.getKSMetaData("ks0"));
            assertNull(Schema.instance.getKSMetaData("ks1"));

            Schema.instance.loadFromDisk();

            assertNotNull(Schema.instance.getKSMetaData("ks0"));
            assertNotNull(Schema.instance.getKSMetaData("ks1"));
        }
        finally
        {
            Gossiper.instance.stop();
        }
    }

    @Test
    public void testConfigurationLoader() throws Exception
    {
        // By default, we should load from the yaml
        Config config = DatabaseDescriptor.loadConfig();
        assertEquals("Test Cluster", config.cluster_name);
        Keyspace.setInitialized();

        // Now try custom loader
        ConfigurationLoader testLoader = new TestLoader();
        System.setProperty("cassandra.config.loader", testLoader.getClass().getName());

        config = DatabaseDescriptor.loadConfig();
        assertEquals("ConfigurationLoader Test", config.cluster_name);
    }

    public static class TestLoader implements ConfigurationLoader
    {
        public Config loadConfig() throws ConfigurationException
        {
            Config testConfig = new Config();
            testConfig.cluster_name = "ConfigurationLoader Test";
            return testConfig;
        }
    }

    static NetworkInterface suitableInterface = null;
    static boolean hasIPv4andIPv6 = false;

    /*
     * Server only accepts interfaces by name if they have a single address
     * OS X seems to always have an ipv4 and ipv6 address on all interfaces which means some tests fail
     * if not checked for and skipped
     */
    @BeforeClass
    public static void selectSuitableInterface() throws Exception {
        Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
        while(interfaces.hasMoreElements()) {
            NetworkInterface intf = interfaces.nextElement();

            System.out.println("Evaluating " + intf.getName());

            if (intf.isLoopback()) {
                suitableInterface = intf;

                boolean hasIPv4 = false;
                boolean hasIPv6 = false;
                Enumeration<InetAddress> addresses = suitableInterface.getInetAddresses();
                while (addresses.hasMoreElements()) {
                    if (addresses.nextElement() instanceof Inet6Address)
                        hasIPv6 = true;
                    else
                        hasIPv4 = true;
                }
                hasIPv4andIPv6 = hasIPv4 && hasIPv6;
                return;
            }
        }
    }

    @Test
    public void testRpcInterface() throws Exception
    {
        Config testConfig = DatabaseDescriptor.loadConfig();
        testConfig.rpc_interface = suitableInterface.getName();
        testConfig.rpc_address = null;
        DatabaseDescriptor.applyAddressConfig(testConfig);

        /*
         * Confirm ability to select between IPv4 and IPv6
         */
        if (hasIPv4andIPv6)
        {
            testConfig = DatabaseDescriptor.loadConfig();
            testConfig.rpc_interface = suitableInterface.getName();
            testConfig.rpc_address = null;
            testConfig.rpc_interface_prefer_ipv6 = true;
            DatabaseDescriptor.applyAddressConfig(testConfig);

            assertEquals(DatabaseDescriptor.getRpcAddress().getClass(), Inet6Address.class);

            testConfig = DatabaseDescriptor.loadConfig();
            testConfig.rpc_interface = suitableInterface.getName();
            testConfig.rpc_address = null;
            testConfig.rpc_interface_prefer_ipv6 = false;
            DatabaseDescriptor.applyAddressConfig(testConfig);

            assertEquals(DatabaseDescriptor.getRpcAddress().getClass(), Inet4Address.class);
        }
        else
        {
            /*
             * Confirm first address of interface is selected
             */
            assertEquals(DatabaseDescriptor.getRpcAddress(), suitableInterface.getInetAddresses().nextElement());
        }
    }

    @Test
    public void testListenInterface() throws Exception
    {
        Config testConfig = DatabaseDescriptor.loadConfig();
        testConfig.listen_interface = suitableInterface.getName();
        testConfig.listen_address = null;
        DatabaseDescriptor.applyAddressConfig(testConfig);

        /*
         * Confirm ability to select between IPv4 and IPv6
         */
        if (hasIPv4andIPv6)
        {
            testConfig = DatabaseDescriptor.loadConfig();
            testConfig.listen_interface = suitableInterface.getName();
            testConfig.listen_address = null;
            testConfig.listen_interface_prefer_ipv6 = true;
            DatabaseDescriptor.applyAddressConfig(testConfig);

            assertEquals(DatabaseDescriptor.getListenAddress().getClass(), Inet6Address.class);

            testConfig = DatabaseDescriptor.loadConfig();
            testConfig.listen_interface = suitableInterface.getName();
            testConfig.listen_address = null;
            testConfig.listen_interface_prefer_ipv6 = false;
            DatabaseDescriptor.applyAddressConfig(testConfig);

            assertEquals(DatabaseDescriptor.getListenAddress().getClass(), Inet4Address.class);
        }
        else
        {
            /*
             * Confirm first address of interface is selected
             */
            assertEquals(DatabaseDescriptor.getRpcAddress(), suitableInterface.getInetAddresses().nextElement());
        }
    }

    @Test
    public void testListenAddress() throws Exception
    {
        Config testConfig = DatabaseDescriptor.loadConfig();
        testConfig.listen_address = suitableInterface.getInterfaceAddresses().get(0).getAddress().getHostAddress();
        testConfig.listen_interface = null;
        DatabaseDescriptor.applyAddressConfig(testConfig);
    }

    @Test
    public void testRpcAddress() throws Exception
    {
        Config testConfig = DatabaseDescriptor.loadConfig();
        testConfig.rpc_address = suitableInterface.getInterfaceAddresses().get(0).getAddress().getHostAddress();
        testConfig.rpc_interface = null;
        DatabaseDescriptor.applyAddressConfig(testConfig);

    }

    @Test
    public void testTokenConfigCheck() throws ConfigurationException, MalformedObjectNameException, MBeanRegistrationException, InstanceNotFoundException
    {
        /**
         * Test for CASSANDRA-14477
         * Test that Cassandra still starts when number of tokens is not specified.
         * Also tests that this Excepts correctly when config is incorrect.
         */

        // Tests that Cassandra still starts when number of tokens is not specified.

        Config testConfig = DatabaseDescriptor.loadConfig();
        MBeanServer mbs = ManagementFactory.getPlatformMBeanServer();

        /**
         * Unregistering Snitches to prevent javax.management.InstanceAlreadyExistsException
         * when applyConfig is called a second time.
         * (first time is in static block in DatabaseDescriptor)
         */

        mbs.unregisterMBean(new ObjectName("org.apache.cassandra.db:type=DynamicEndpointSnitch"));
        mbs.unregisterMBean(new ObjectName("org.apache.cassandra.db:type=EndpointSnitchInfo"));
        testConfig.initial_token = "0,256,1024";
        DatabaseDescriptor.applyConfig(testConfig);

        // Tests that applyConfig() does not Except when number of tokens in inital_token matches num_tokens.
        Config testMatchingConfig = DatabaseDescriptor.loadConfig();
        // Unregistering snitches gain
        mbs.unregisterMBean(new ObjectName("org.apache.cassandra.db:type=DynamicEndpointSnitch"));
        mbs.unregisterMBean(new ObjectName("org.apache.cassandra.db:type=EndpointSnitchInfo"));
        testMatchingConfig.initial_token = "0,256,1024";
        testMatchingConfig.num_tokens = 3;
        DatabaseDescriptor.applyConfig(testMatchingConfig);

        // Tests that applyConfig() Excepts when number of tokens in inital_token does not match num_tokens.
        Config testBrokenConfig = DatabaseDescriptor.loadConfig();
        testBrokenConfig.initial_token = "0,256,1024";
        testBrokenConfig.num_tokens = 4;

        try
        {
            // Unregistering snitches again
            mbs.unregisterMBean(new ObjectName("org.apache.cassandra.db:type=DynamicEndpointSnitch"));
            mbs.unregisterMBean(new ObjectName("org.apache.cassandra.db:type=EndpointSnitchInfo"));
            DatabaseDescriptor.applyConfig(testBrokenConfig);
            fail("Did not throw exception with incorrect config"); // Should not get here
        } catch (ConfigurationException e) {
            // Expecting exception
        }
    }
}
