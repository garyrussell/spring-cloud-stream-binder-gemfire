/*
 * Copyright 2015 the original author or authors.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.springframework.cloud.stream.binder.gemfire;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.Collections;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.TimeUnit;

import com.gemstone.gemfire.cache.Cache;
import com.gemstone.gemfire.distributed.LocatorLauncher;
import com.oracle.tools.runtime.LocalPlatform;
import com.oracle.tools.runtime.PropertiesBuilder;
import com.oracle.tools.runtime.concurrent.RemoteCallable;
import com.oracle.tools.runtime.console.SystemApplicationConsole;
import com.oracle.tools.runtime.java.JavaApplication;
import com.oracle.tools.runtime.java.LocalJavaApplicationBuilder;
import com.oracle.tools.runtime.java.SimpleJavaApplication;
import com.oracle.tools.runtime.java.SimpleJavaApplicationSchema;
import org.junit.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import org.springframework.cloud.stream.binder.BinderPropertyKeys;
import org.springframework.cloud.stream.binder.PartitionSelectorStrategy;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.data.gemfire.CacheFactoryBean;
import org.springframework.expression.spel.support.StandardEvaluationContext;
import org.springframework.integration.test.util.SocketUtils;
import org.springframework.messaging.Message;
import org.springframework.messaging.MessageHandler;
import org.springframework.messaging.MessagingException;
import org.springframework.messaging.SubscribableChannel;
import org.springframework.messaging.support.ExecutorSubscribableChannel;
import org.springframework.messaging.support.GenericMessage;

/**
 * Tests for {@link GemfireMessageChannelBinder}.
 *
 * @author Patrick Peralta
 */
public class GemfireBinderTests {
	private static final Logger logger = LoggerFactory.getLogger(GemfireBinderTests.class);

	/**
	 * Timeout value in milliseconds for operations to complete.
	 */
	private static final long TIMEOUT = 30000;

	/**
	 * Payload of test message.
	 */
	private static final String MESSAGE_PAYLOAD = "hello world";

	/**
	 * Name of binding used for producer and consumer bindings.
	 */
	private static final String BINDING_NAME = "test";

	/**
	 * Name of GemFire Locator.
	 */
	public static final String LOCATOR_NAME = "locator1";


	/**
	 * Test basic message sending functionality.
	 *
	 * @throws Exception
	 */
	@Test
	public void testMessageSendReceive() throws Exception {
		testMessageSendReceive(null, false);
	}

	/**
	 * Test usage of partition selector.
	 *
	 * @throws Exception
	 */
	@Test
	public void testPartitionedMessageSendReceive() throws Exception {
		testMessageSendReceive(null, true);
	}

	/**
	 * Test consumer group functionality.
	 *
	 * @throws Exception
	 */
	@Test
	public void testMessageSendReceiveConsumerGroups() throws Exception {
		testMessageSendReceive(new String[]{"a", "b"}, false);
	}

	/**
	 * Test message sending functionality.
	 *
	 * @param groups consumer groups; may be {@code null}
	 * @throws Exception
	 */
	private void testMessageSendReceive(String[] groups, boolean partitioned) throws Exception {
		LocatorLauncher locatorLauncher = null;
		JavaApplication[] consumers = new JavaApplication[groups == null ? 1 : groups.length];
		JavaApplication producer = null;
		int locatorPort = SocketUtils.findAvailableServerSocket();

		try {
			locatorLauncher = new LocatorLauncher.Builder()
					.setMemberName(LOCATOR_NAME)
					.setPort(locatorPort)
					.setRedirectOutput(true)
					.build();

			locatorLauncher.start();
			locatorLauncher.waitOnStatusResponse(TIMEOUT, 5, TimeUnit.MILLISECONDS);

			Properties moduleProperties = new Properties();
			moduleProperties.setProperty("gemfire.locators", String.format("localhost[%d]", locatorPort));
			if (partitioned) {
				moduleProperties.setProperty("partitioned", "true");
			}

			for (int i = 0; i < consumers.length; i++) {
				consumers[i] = launch(Consumer.class, moduleProperties,
						groups == null ? null : Collections.singletonList(groups[i]));
			}
			for (JavaApplication consumer : consumers) {
				waitForConsumer(consumer);
			}

			producer = launch(Producer.class, moduleProperties, null);

			for (JavaApplication consumer : consumers) {
				assertEquals(MESSAGE_PAYLOAD, waitForMessage(consumer));
			}

			if (partitioned) {
				assertTrue(partitionSelectorUsed(producer));
			}
		}
 		finally {
			if (producer != null) {
				producer.close();
			}
			for (JavaApplication consumer : consumers) {
				if (consumer != null) {
					consumer.close();
				}
			}
			if (locatorLauncher != null) {
				locatorLauncher.stop();
			}
			cleanLocatorFiles(locatorPort);
		}
	}

	/**
	 * Remove the files generated by the GemFire Locator.
	 */
	private void cleanLocatorFiles(int port) {
		Path workingPath = Paths.get(".");
		deleteFile(workingPath.resolve(String.format("ConfigDiskDir_%s", LOCATOR_NAME)).toFile());
		deleteFile(workingPath.resolve(String.format("%s.log", LOCATOR_NAME)).toFile());
		deleteFile(workingPath.resolve(String.format("locator%dstate.dat", port)).toFile());
		deleteFile(workingPath.resolve(String.format("locator%dviews.log", port)).toFile());
		deleteFile(workingPath.resolve("BACKUPDEFAULT.if").toFile());
		deleteFile(workingPath.resolve("DRLK_IFDEFAULT.lk").toFile());
	}

	/**
	 * Deletes the file or directory denoted by the given {@link File}.
	 * If this {@code File} denotes a directory, then the files contained
	 * in the directory will be recursively deleted.
	 *
	 * @param file the file to be deleted
	 */
	private void deleteFile(File file) {
		if (file.isDirectory()) {
			File[] files = file.listFiles();
			if (files == null) {
				logger.warn("Could not delete directory {}", file);
			}
			else {
				for (File f : files) {
					deleteFile(f);
				}
			}
		}
		if (!file.setWritable(true)) {
			logger.warn("Could not set write permissions on {}", file);
		}
		if (!file.delete()) {
			logger.warn("Could not delete {}", file);
		}
	}

	/**
	 * Block the executing thread until the consumer is bound.
	 *
	 * @param consumer the consumer application
	 * @throws InterruptedException if the thread is interrupted
	 * @throws AssertionError if the consumer is not bound after
	 * {@value #TIMEOUT} milliseconds
	 */
	private void waitForConsumer(JavaApplication consumer) throws InterruptedException {
		long start = System.currentTimeMillis();
		while (System.currentTimeMillis() < start + TIMEOUT) {
			if (consumer.submit(new ConsumerBoundChecker())) {
				return;
			}
			else {
				Thread.sleep(1000);
			}
		}
		assertTrue("Consumer not bound", consumer.submit(new ConsumerBoundChecker()));
	}

	private boolean partitionSelectorUsed(JavaApplication producer) throws InterruptedException {
		return producer.submit(new ProducerPartitionSelectorChecker());
	}

	/**
	 * Block the executing thread until a message is received by the
	 * consumer application, or until {@value #TIMEOUT} milliseconds elapses.
	 *
	 * @param consumer the consumer application
	 * @return the message payload that was received
	 * @throws InterruptedException if the thread is interrupted
	 */
	private String waitForMessage(JavaApplication consumer) throws InterruptedException {
		long start = System.currentTimeMillis();
		String message = null;
		while (System.currentTimeMillis() < start + TIMEOUT) {
			message = consumer.submit(new ConsumerMessageExtractor());
			if (message == null) {
				Thread.sleep(1000);
			}
			else {
				break;
			}
		}
		return message;
	}

	/**
	 * Launch the given class's {@code main} method in a separate JVM.
	 *
	 * @param clz               class to launch
	 * @param systemProperties  system properties for new process
	 * @param args              command line arguments
	 * @return launched application
	 *
	 * @throws IOException if an exception was thrown launching the process
	 */
	private JavaApplication launch(Class<?> clz, Properties systemProperties,
			List<String> args) throws IOException {
		String classpath = System.getProperty("java.class.path");

		logger.info("Launching {}", clz);
		logger.info("	args: {}", args);
		logger.info("	properties: {}", systemProperties);
		logger.info("	classpath: {}", classpath);

		SimpleJavaApplicationSchema schema = new SimpleJavaApplicationSchema(clz.getName(), classpath);
		if (args != null) {
			for (String arg : args) {
				schema.addArgument(arg);
			}
		}
		if (systemProperties != null) {
			schema.setSystemProperties(new PropertiesBuilder(systemProperties));
		}
		schema.setWorkingDirectory(Files.createTempDirectory(null).toFile());

		LocalJavaApplicationBuilder<SimpleJavaApplication> builder =
				new LocalJavaApplicationBuilder<>(LocalPlatform.getInstance());
		return builder.realize(schema, clz.getName(), new SystemApplicationConsole());
	}

	/**
	 * Create a {@link Cache} with hard coded properties for testing.
	 *
	 * @return Cache for testing
	 *
	 * @throws Exception
	 */
	private static Cache createCache() throws Exception {
		CacheFactoryBean bean = new CacheFactoryBean();
		Properties properties = new Properties();
		properties.put("locators", "localhost[7777]");
		properties.put("log-level", "warning");
		properties.put("mcast-port", "0");
		bean.setProperties(properties);

		return bean.getObject();
	}

	public static class StubPartitionSelectorStrategy implements PartitionSelectorStrategy {
		public volatile boolean invoked = false;

		@Override
		public int selectPartition(Object key, int partitionCount) {
			logger.warn("Selecting partition for key {}; partition count: {}", key, partitionCount);
			System.out.printf("Selecting partition for key %s; partition count: %d", key, partitionCount);
			invoked = true;
			return 1;
		}
	}

	/**
	 * Producer application that binds a channel to a {@link GemfireMessageChannelBinder}
	 * and sends a test message.
	 */
	public static class Producer {
		private static volatile StubPartitionSelectorStrategy partitionSelectorStrategy =
				new StubPartitionSelectorStrategy();

		public static void main(String[] args) throws Exception {
			GemfireMessageChannelBinder binder = new GemfireMessageChannelBinder(createCache());
			binder.setApplicationContext(new GenericApplicationContext());
			binder.setIntegrationEvaluationContext(new StandardEvaluationContext());
			if (Boolean.getBoolean("partitioned")) {
				System.out.println("setting partition selector");
				binder.setPartitionSelector(partitionSelectorStrategy);
			}
			binder.afterPropertiesSet();

			SubscribableChannel producerChannel = new ExecutorSubscribableChannel();

			Properties properties = new Properties();
			properties.setProperty(BinderPropertyKeys.PARTITION_KEY_EXPRESSION, "payload");
			binder.bindProducer(BINDING_NAME, producerChannel, properties);

			Message<String> message = new GenericMessage<>(MESSAGE_PAYLOAD);
			producerChannel.send(message);

			Thread.sleep(Long.MAX_VALUE);
		}
	}

	/**
	 * Consumer application that binds a channel to a {@link GemfireMessageChannelBinder}
	 * and stores the received message payload.
	 */
	public static class Consumer {

		/**
		 * Flag that indicates if the consumer has been bound.
		 */
		private static volatile boolean isBound = false;

		/**
		 * Payload of last received message.
		 */
		private static volatile String messagePayload;

		/**
		 * Main method.
		 *
		 * @param args if present, first arg is consumer group name
		 * @throws Exception
		 */
		public static void main(String[] args) throws Exception {
			GemfireMessageChannelBinder binder = new GemfireMessageChannelBinder(createCache());
			binder.setApplicationContext(new GenericApplicationContext());
			binder.setIntegrationEvaluationContext(new StandardEvaluationContext());
			binder.setBatchSize(1);
			binder.afterPropertiesSet();

			SubscribableChannel consumerChannel = new ExecutorSubscribableChannel();
			consumerChannel.subscribe(new MessageHandler() {
				@Override
				public void handleMessage(Message<?> message) throws MessagingException {
					messagePayload = (String) message.getPayload();
				}
			});
			String group = null;
			if (args.length > 0) {
				group = args[0];
			}
			binder.bindConsumer(BINDING_NAME, group, consumerChannel, new Properties());
			isBound = true;

			Thread.sleep(Long.MAX_VALUE);
		}
	}

	public static class ConsumerBoundChecker implements RemoteCallable<Boolean> {
		@Override
		public Boolean call() throws Exception {
			return Consumer.isBound;
		}
	}

	public static class ConsumerMessageExtractor implements RemoteCallable<String> {
		@Override
		public String call() throws Exception {
			return Consumer.messagePayload;
		}
	}

	public static class ProducerPartitionSelectorChecker implements RemoteCallable<Boolean> {
		@Override
		public Boolean call() throws Exception {
			return Producer.partitionSelectorStrategy.invoked;
		}
	}

}
