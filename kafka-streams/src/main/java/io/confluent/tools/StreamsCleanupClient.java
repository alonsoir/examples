/**
 * Copyright 2016 Confluent Inc.
 * <p>
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * <p>
 * http://www.apache.org/licenses/LICENSE-2.0
 * <p>
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package io.confluent.tools;

import joptsimple.OptionException;
import joptsimple.OptionParser;
import joptsimple.OptionSet;
import joptsimple.OptionSpec;
import kafka.admin.TopicCommand;
import kafka.utils.ZkUtils;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.security.JaasUtils;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.utils.Utils;

import java.io.File;
import java.io.IOException;
import java.util.Collections;
import java.util.LinkedList;
import java.util.List;
import java.util.Properties;
import java.util.Set;

/**
 * {@link StreamsCleanupClient} resets a Kafka Streams application to the very beginning. Thus, an application can
 * reprocess the whole input from scratch.
 * <p>
 * This includes, setting source topic offsets to zero, skipping over all intermediate user topics,
 * deleting all internally created topics, and deleting local state stores.
 */
public class StreamsCleanupClient {
    private static final int EXIT_CODE_SUCCESS = 0;
    private static final int EXIT_CODE_ERROR = 1;

    private static OptionSpec<String> bootstrapServerOption;
    private static OptionSpec<String> zookeeperOption;
    private static OptionSpec<String> applicationIdOption;
    private static OptionSpec<String> sourceTopicsOption;
    private static OptionSpec<String> intermediateTopicsOption;
    private static OptionSpec<String> stateDirOption;

    private OptionSet options = null;
    private final List<String> allTopics = new LinkedList<>();

    public void run(final String[] args) {
        int exitCode = EXIT_CODE_SUCCESS;

        ZkUtils zkUtils = null;
        try {
            parseArguments(args);

            zkUtils = ZkUtils.apply(this.options.valueOf(zookeeperOption),
                30000,
                30000,
                JaasUtils.isZkSecurityEnabled());

            this.allTopics.clear();
            this.allTopics.addAll(scala.collection.JavaConversions.seqAsJavaList(zkUtils.getAllTopics()));

            resetSourceTopicOffsets();
            seekToEndIntermediateTopics();
            deleteInternalTopics(zkUtils);
            removeLocalStateStore();
        } catch (final Exception e) {
            exitCode = EXIT_CODE_ERROR;
            System.err.println("ERROR: " + e.getMessage());
        } finally {
            if (zkUtils != null) {
                zkUtils.close();
            }
        }
        System.exit(exitCode);
    }

    private void parseArguments(final String[] args) throws IOException {
        final OptionParser optionParser = new OptionParser();
        applicationIdOption = optionParser.accepts("application-id", "The Kafka Streams application ID (application.id)")
            .withRequiredArg()
            .ofType(String.class)
            .describedAs("id")
            .required();
        bootstrapServerOption = optionParser.accepts("bootstrap-servers", "Comma-separated list of broker urls with format: HOST1:PORT1,HOST2:PORT2")
            .withRequiredArg()
            .ofType(String.class)
            .defaultsTo("localhost:9092")
            .describedAs("urls");
        zookeeperOption = optionParser.accepts("zookeeper", "Format: HOST:POST")
            .withRequiredArg()
            .ofType(String.class)
            .defaultsTo("localhost:2181")
            .describedAs("url");
        sourceTopicsOption = optionParser.accepts("source-topics", "Comma-separated list of user source topics")
            .withRequiredArg()
            .ofType(String.class)
            .withValuesSeparatedBy(',')
            .describedAs("list");
        intermediateTopicsOption = optionParser.accepts("intermediate-topics", "Comma-separated list of intermediate user topics")
            .withRequiredArg()
            .ofType(String.class)
            .withValuesSeparatedBy(',')
            .describedAs("list");
        stateDirOption = optionParser.accepts("state-dir", "Local state store directory (state.dir)")
            .withRequiredArg()
            .ofType(String.class)
            .defaultsTo("/var/lib/kafka-streams")
            .describedAs("dir");

        try {
            this.options = optionParser.parse(args);
        } catch (final OptionException e) {
            optionParser.printHelpOn(System.err);
            throw e;
        }
    }

    private void resetSourceTopicOffsets() {
        final List<String> sourceTopics = this.options.valuesOf(sourceTopicsOption);

        if (sourceTopics.size() == 0) {
            System.out.println("No source topics specified, skipping resetting source topic offsets.");
            return;
        }

        System.out.println("Resetting offsets to zero for topics " + sourceTopics);

        final Properties config = new Properties();
        config.setProperty(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, this.options.valueOf(bootstrapServerOption));
        config.setProperty(ConsumerConfig.GROUP_ID_CONFIG, this.options.valueOf(applicationIdOption));
        config.setProperty(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");

        for (final String topic : sourceTopics) {
            if (this.allTopics.contains(topic)) {
                System.out.println("Topic: " + topic);

                try (final KafkaConsumer<byte[], byte[]> client = new KafkaConsumer<>(config, new ByteArrayDeserializer(), new ByteArrayDeserializer())) {
                    client.subscribe(Collections.singleton(topic));
                    client.poll(1);

                    final Set<TopicPartition> partitions = client.assignment();
                    client.seekToBeginning(partitions);
                    for (final TopicPartition p : partitions) {
                        client.position(p);
                    }
                    client.commitSync();
                } catch (final RuntimeException e) {
                    System.err.println("ERROR: Resetting offsets for source topic " + topic + " failed.");
                    throw e;
                }
            } else {
                System.out.println("Topic " + topic + " not found. Skipping.");
            }
        }

        System.out.println("Done.");
    }

    private void seekToEndIntermediateTopics() {
        final List<String> intermediateTopics = this.options.valuesOf(intermediateTopicsOption);

        if (intermediateTopics.size() == 0) {
            System.out.println("No intermediate user topics specified, skipping seek-to-end for user topic offsets.");
            return;
        }

        System.out.println("Seek-to-end for intermediate user topics " + intermediateTopics);

        final Properties config = new Properties();
        config.setProperty(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, this.options.valueOf(bootstrapServerOption));
        config.setProperty(ConsumerConfig.GROUP_ID_CONFIG, this.options.valueOf(applicationIdOption));
        config.setProperty(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, "false");

        for (final String topic : intermediateTopics) {
            if (this.allTopics.contains(topic)) {
                System.out.println("Topic: " + topic);

                try (final KafkaConsumer<byte[], byte[]> client = new KafkaConsumer<>(config, new ByteArrayDeserializer(), new ByteArrayDeserializer())) {
                    client.subscribe(Collections.singleton(topic));
                    client.poll(1);

                    final Set<TopicPartition> partitions = client.assignment();
                    client.seekToEnd(partitions);
                    for (final TopicPartition p : partitions) {
                        client.position(p);
                    }
                    client.commitSync();
                } catch (final RuntimeException e) {
                    System.err.println("ERROR: Seek-to-end for topic " + topic + " failed.");
                    throw e;
                }
            } else {
                System.out.println("Topic " + topic + " not found. Skipping.");
            }
        }

        System.out.println("Done.");
    }

    private void deleteInternalTopics(final ZkUtils zkUtils) {
        System.out.println("Deleting all internal/auto-created topics for application " + this.options.valueOf(applicationIdOption));

        for (final String topic : this.allTopics) {
            if (isInternalStreamsTopic(topic)) {
                final TopicCommand.TopicCommandOptions commandOptions = new TopicCommand.TopicCommandOptions(new String[]{
                    "--zookeeper", this.options.valueOf(zookeeperOption),
                    "--delete", "--topic", topic});
                try {
                    TopicCommand.deleteTopic(zkUtils, commandOptions);
                } catch (final RuntimeException e) {
                    System.err.println("ERROR: Deleting topic " + topic + " failed.");
                    throw e;
                }
            }
        }

        System.out.println("Done.");
    }

    private boolean isInternalStreamsTopic(final String topicName) {
        return topicName.startsWith(this.options.valueOf(applicationIdOption) + "-")
            && (topicName.endsWith("-changelog") || topicName.endsWith("-repartition"));
    }

    private void removeLocalStateStore() {
        final String appId = this.options.valueOf(applicationIdOption);
        final File stateStore = new File(this.options.valueOf(stateDirOption) + File.separator + appId);
        if (!stateStore.exists()) {
            System.out.println("Nothing to delete. Local state store directory does not exist.");
            return;
        }
        final String stateStorePath = stateStore.getAbsolutePath();
        if (!stateStore.isDirectory()) {
            System.err.println("ERROR: " + stateStorePath + " is not a directory.");
            return;
        }

        System.out.println("Removing local Kafka Streams application data in " + stateStorePath
            + " for application " + appId);
        Utils.delete(stateStore);
        System.out.println("Deleted " + stateStorePath);
    }

    public static void main(final String[] args) {
        new StreamsCleanupClient().run(args);
    }

}
