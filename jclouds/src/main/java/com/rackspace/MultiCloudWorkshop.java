package com.rackspace;

import com.google.common.base.Charsets;
import com.google.common.base.Joiner;
import com.google.common.base.Stopwatch;
import com.google.common.collect.ImmutableSet;
import com.google.common.io.Files;
import com.google.common.io.Resources;
import com.google.inject.Module;
import org.jclouds.ContextBuilder;
import org.jclouds.compute.ComputeService;
import org.jclouds.compute.ComputeServiceContext;
import org.jclouds.compute.RunNodesException;
import org.jclouds.compute.config.ComputeServiceProperties;
import org.jclouds.compute.domain.NodeMetadata;
import org.jclouds.compute.domain.Template;
import org.jclouds.compute.options.RunScriptOptions;
import org.jclouds.compute.options.TemplateOptions;
import org.jclouds.logging.slf4j.config.SLF4JLoggingModule;
import org.jclouds.scriptbuilder.ScriptBuilder;
import org.jclouds.scriptbuilder.domain.OsFamily;
import org.jclouds.sshj.config.SshjSshClientModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.TimeUnit;

import static com.google.common.base.Charsets.UTF_8;
import static com.google.common.collect.Iterables.getOnlyElement;
import static com.google.common.collect.Lists.newArrayList;
import static com.google.common.collect.Maps.newHashMap;
import static com.google.common.io.Resources.getResource;
import static com.google.common.io.Resources.newInputStreamSupplier;
import static com.rackspace.MultiCloudWorkshop.ProviderProperty.*;
import static java.lang.String.format;
import static org.jclouds.compute.config.ComputeServiceProperties.POLL_INITIAL_PERIOD;
import static org.jclouds.compute.config.ComputeServiceProperties.POLL_MAX_PERIOD;
import static org.jclouds.compute.predicates.NodePredicates.inGroup;
import static org.jclouds.openstack.nova.v2_0.domain.zonescoped.ZoneAndId.fromZoneAndId;
import static org.jclouds.scriptbuilder.domain.Statements.exec;

public class MultiCloudWorkshop {
    public static final String MCW = "multi-cloud-workshop";
    public static final String PUBLIC_KEY_FILENAME = MCW + ".pub";
    public static final String PRIVATE_KEY_FILENAME = MCW + ".key";
    public static final String LOAD_BALANCER = MCW + "-lb";
    public static final String DATABASE = MCW + "-db";
    public static final String WEB_SERVER_01 = MCW + "-webserver-01";
    public static final String WEB_SERVER_02 = MCW + "-webserver-02";

    private final ComputeService computeService;
    private final Map<ProviderProperty, String> providerProps = newHashMap();

    private final Logger logger = LoggerFactory.getLogger(MultiCloudWorkshop.class);
    private final Stopwatch timer;

    public static void main(String[] args) {
        MultiCloudWorkshop multiCloudWorkshop = null;

        try {
            multiCloudWorkshop = new MultiCloudWorkshop();

            Map<String, NodeMetadata> nameToNode = multiCloudWorkshop.createServers();
            multiCloudWorkshop.setupDatabase(nameToNode.get(DATABASE));
            multiCloudWorkshop.setupWebServers(getWebServers(nameToNode));
            multiCloudWorkshop.setupLoadBalancer(nameToNode.get(LOAD_BALANCER), getWebServers(nameToNode));
//            multiCloudWorkshop.printResults(node);
//            multiCloudWorkshop.deleteNodes();
        } catch (Throwable e) {
            e.printStackTrace();
        } finally {
            if (multiCloudWorkshop != null) {
                multiCloudWorkshop.close();
            }
        }
    }

    public MultiCloudWorkshop() throws IOException {
        timer = Stopwatch.createStarted();
        initializeProviderProperties();

        logger.info(format("Multi-Cloud Workshop on %s", providerProps.get(NAME)));

        Iterable<Module> modules = ImmutableSet.<Module>of(
                new SLF4JLoggingModule(),
                new SshjSshClientModule());

        Properties overrides = new Properties();
        overrides.setProperty(POLL_INITIAL_PERIOD, "30000");
        overrides.setProperty(POLL_MAX_PERIOD, "30000");
        overrides.setProperty(ComputeServiceProperties.TIMEOUT_SCRIPT_COMPLETE, "1200000");

        ComputeServiceContext context = ContextBuilder.newBuilder(providerProps.get(NAME))
                .credentials(providerProps.get(IDENTITY), providerProps.get(CREDENTIAL))
                .modules(modules)
                .overrides(overrides)
                .buildView(ComputeServiceContext.class);
        computeService = context.getComputeService();
    }

    private void initializeProviderProperties() throws IOException {
        Properties props = new Properties();
        props.load(newInputStreamSupplier(getResource("provider.properties")).getInput());

        String provider = props.getProperty(PROVIDER.toString());

        providerProps.put(PROVIDER, provider);
        providerProps.put(NAME, props.getProperty(String.format("%s.%s", provider, NAME)));
        providerProps.put(IDENTITY, props.getProperty(format("%s.%s", provider, IDENTITY)));
        providerProps.put(CREDENTIAL, props.getProperty(format("%s.%s", provider, CREDENTIAL)));
        providerProps.put(LOCATION, props.getProperty(format("%s.%s", provider, LOCATION)));
        providerProps.put(IMAGE, props.getProperty(format("%s.%s", provider, IMAGE)));
        providerProps.put(HARDWARE, props.getProperty(format("%s.%s", provider, HARDWARE)));
    }

    private Map<String, NodeMetadata> createServers() throws RunNodesException, IOException {
        Set<String> nodeNames = ImmutableSet.of(DATABASE, WEB_SERVER_01, WEB_SERVER_02, LOAD_BALANCER);

        logger.info(format("Creating servers %s", Joiner.on(", ").join(nodeNames)));
        logger.info("Check log/jclouds-wire.log for progress");

        String publicKey = Resources.toString(getResource(PUBLIC_KEY_FILENAME), Charsets.UTF_8);
        String privateKey = Resources.toString(getResource(PRIVATE_KEY_FILENAME), Charsets.UTF_8);

        TemplateOptions options = computeService.templateOptions()
                .nodeNames(nodeNames)
                .authorizePublicKey(publicKey)
                .overrideLoginPrivateKey(privateKey)
                .inboundPorts(22, 80, 3000, 3306);


//        if (providerProps.get(PROVIDER).equals("aws")) {
//            options = AWSEC2TemplateOptions.Builder.nodeNames(nodeNames).keyPair(MCW).overrideLoginPrivateKey(privateKey).securityGroups(MCW);
//        }
//        else {
//            options = NovaTemplateOptions.Builder.nodeNames(nodeNames).keyPairName(MCW).overrideLoginPrivateKey(privateKey).securityGroups(MCW);
//        }

        String hardwareId = providerProps.get(HARDWARE);

        if (!providerProps.get(PROVIDER).equals("aws")) {
            hardwareId = fromZoneAndId(providerProps.get(LOCATION), providerProps.get(HARDWARE)).slashEncode();
        }

        Template template = computeService.templateBuilder()
                .imageNameMatches(providerProps.get(IMAGE))
                .locationId(providerProps.get(LOCATION))
                .hardwareId(hardwareId)
                .options(options)
                .build();

        Set<? extends NodeMetadata> nodes = computeService.createNodesInGroup(MCW, 4, template);
        Map<String, NodeMetadata> nameToNode = newHashMap();

        logger.info("Created servers:");
        for (NodeMetadata node: nodes) {
            String name = node.getName();
            String publicIpAddress = getOnlyElement(node.getPublicAddresses());
            String user = node.getCredentials().getUser();

            logger.info(format("  %-40s ssh -i %s %s@%s", name, PRIVATE_KEY_FILENAME, user, publicIpAddress));

//            nameToNode.put(name, node);
        }

        Iterator<? extends NodeMetadata> nodeIterator = nodes.iterator();
        nameToNode.put(DATABASE, nodeIterator.next());
        nameToNode.put(WEB_SERVER_01, nodeIterator.next());
        nameToNode.put(WEB_SERVER_02, nodeIterator.next());
        nameToNode.put(LOAD_BALANCER, nodeIterator.next());

        return nameToNode;
    }

    private void setupDatabase(NodeMetadata node) throws IOException {
        logger.info(format("Database %s configuration started", node.getName()));
        logger.info("Check log/jclouds-ssh.log for progress");


        RunScriptOptions options = RunScriptOptions.Builder
                .blockOnComplete(true);

        String script = new ScriptBuilder()
                .addStatement(exec("sleep 1"))
                .addStatement(exec("sudo apt-get -q -y update"))
                .addStatement(exec("sudo apt-get -q -y upgrade"))
                .addStatement(exec("sudo debconf-set-selections <<< 'mysql-server mysql-server/root_password password admin123'"))
                .addStatement(exec("sudo debconf-set-selections <<< 'mysql-server mysql-server/root_password_again password admin123'"))
                .addStatement(exec("sudo apt-get -q -y install mysql-server"))
                .addStatement(exec(format("sudo sed -ie 's/bind-address.*/bind-address = %s/' /etc/mysql/my.cnf", getOnlyElement(node.getPrivateAddresses()))))
                .addStatement(exec("sudo service mysql restart"))
                .render(OsFamily.UNIX);

        computeService.runScriptOnNode(node.getId(), script, options);

        logger.info("Database configuration complete");
    }

    private void setupWebServers(List<NodeMetadata> webServerNodes) {
        for (NodeMetadata node: webServerNodes) {
            logger.info(format("Web server %s configuration started", node.getName()));
            logger.info("Check log/jclouds-ssh.log for progress");

            RunScriptOptions options = RunScriptOptions.Builder
                    .blockOnComplete(true);

            String script = new ScriptBuilder()
                    .addStatement(exec("sleep 1"))
                    .addStatement(exec("sudo apt-get -q -y update"))
                    .addStatement(exec("sudo apt-get -q -y upgrade"))
                    .addStatement(exec("sudo apt-get -q -y install apache2"))
                    .addStatement(exec("echo 'Hello from " + node.getPublicAddresses() + "' > /var/www/index.html"))
                    .render(OsFamily.UNIX);

            computeService.runScriptOnNode(node.getId(), script, options);

            logger.info("Web server configuration complete");
        }
    }

    private static List<NodeMetadata> getWebServers(Map<String, NodeMetadata> nameToNode) {
        List<NodeMetadata> webServerNodes = newArrayList();

        for (String name: nameToNode.keySet()) {
            if (name.contains("webserver")) {
                webServerNodes.add(nameToNode.get(name));
            }
        }

        return webServerNodes;
    }

    private void setupLoadBalancer(NodeMetadata node, List<NodeMetadata> webServerNodes) {
        logger.info(format("Load balancer %s configuration started", node.getName()));
        logger.info("Check log/jclouds-ssh.log for progress");

        RunScriptOptions options = RunScriptOptions.Builder
                .blockOnComplete(true);

        String script = new ScriptBuilder()
                .addStatement(exec("sleep 1"))
                .addStatement(exec("sudo apt-get -q -y update"))
                .addStatement(exec("sudo apt-get -q -y upgrade"))
                .addStatement(exec("sudo apt-get -q -y install haproxy"))
                .addStatement(exec("sudo sed -ie 's/ENABLED=0/ENABLED=1/' /etc/default/haproxy"))
                .addStatement(exec(format("sudo sh -c \"echo '%s' > /etc/haproxy/haproxy.cfg\"", getLoadBalancerConfig(webServerNodes))))
                .addStatement(exec("sudo service haproxy restart"))
                .render(OsFamily.UNIX);

        computeService.runScriptOnNode(node.getId(), script, options);

        logger.info("Load balancer configuration complete");
    }

    private String getLoadBalancerConfig(List<NodeMetadata> webServerNodes) {
        StringBuilder configBuilder = new StringBuilder();
        configBuilder.append("global\n");
        configBuilder.append("        log 127.0.0.1   local0\n");
        configBuilder.append("        log 127.0.0.1   local1 notice\n");
        configBuilder.append("        #log loghost    local0 info\n");
        configBuilder.append("        maxconn 4096\n");
        configBuilder.append("        #chroot /usr/share/haproxy\n");
        configBuilder.append("        user haproxy\n");
        configBuilder.append("        group haproxy\n");
        configBuilder.append("        daemon\n");
        configBuilder.append("        #debug\n");
        configBuilder.append("        #quiet\n");
        configBuilder.append("        stats socket /tmp/haproxy\n");
        configBuilder.append("\n");
        configBuilder.append("defaults\n");
        configBuilder.append("        log global\n");
        configBuilder.append("        mode http\n");
        configBuilder.append("        option httplog\n");
        configBuilder.append("        option dontlognull\n");
        configBuilder.append("        retries 3\n");
        configBuilder.append("        option redispatch\n");
        configBuilder.append("        maxconn 2000\n");
        configBuilder.append("        contimeout 5000\n");
        configBuilder.append("        clitimeout 50000\n");
        configBuilder.append("        srvtimeout 50000\n");
        configBuilder.append("\n");
        configBuilder.append("listen  web-proxy 0.0.0.0:80\n");
        configBuilder.append("        mode http\n");
        configBuilder.append("        balance roundrobin\n");

        for (NodeMetadata webServerNode: webServerNodes) {
            configBuilder.append(format("        server %s %s\n", webServerNode.getName(), getOnlyElement(webServerNode.getPrivateAddresses())));
        }

        return configBuilder.toString();
    }

    private void printResults(NodeMetadata node) throws IOException {
        String publicAddress = node.getPublicAddresses().iterator().next();
        String provider = computeService.getContext().toString().contains("rackspace") ? "Rackspace" : "HP";

        System.out.println("Provider: " + provider);

        if (node.getCredentials().getOptionalPrivateKey().isPresent()) {
            Files.write(node.getCredentials().getPrivateKey(), new File("jclouds.pem"), UTF_8);
            System.out.println("  Login: ssh -i jclouds.pem " + node.getCredentials().getUser() + "@" + publicAddress);
        } else {
            System.out.println("  Login: ssh " + node.getCredentials().getUser() + "@" + publicAddress);
            System.out.println("  Password: " + node.getCredentials().getPassword());
        }

        System.out.println("  Go to http://" + publicAddress);
    }

    /**
     * This will delete all servers in group "jclouds-workshop"
     */
    private void deleteNodes() {
        System.out.println("Delete Nodes");

        try {
            Set<? extends NodeMetadata> servers = computeService.destroyNodesMatching(inGroup("jclouds-workshop"));

            for (NodeMetadata nodeMetadata : servers) {
                System.out.println("  " + nodeMetadata);
            }
        } catch (Throwable e) {
            e.printStackTrace();
        }
    }

    private void close() {
        computeService.getContext().close();
        logger.info(format("The code took %s to run", timer.stop()));
    }

    public enum ProviderProperty {
        PROVIDER("provider"),
        NAME("name"),
        IDENTITY("identity"),
        CREDENTIAL("credential"),
        LOCATION("location"),
        IMAGE("image"),
        HARDWARE("hardware");

        private final String text;

        private ProviderProperty(final String text) {
            this.text = text;
        }

        @Override
        public String toString() {
            return text;
        }
    }
}
