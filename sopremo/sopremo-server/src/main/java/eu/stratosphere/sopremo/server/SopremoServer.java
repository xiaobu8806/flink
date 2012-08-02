/***********************************************************************************************************************
 *
 * Copyright (C) 2010 by the Stratosphere project (http://stratosphere.eu)
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not use this file except in compliance with
 * the License. You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software distributed under the License is distributed on
 * an "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the License for the
 * specific language governing permissions and limitations under the License.
 *
 **********************************************************************************************************************/
package eu.stratosphere.sopremo.server;

import java.io.Closeable;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledThreadPoolExecutor;

import eu.stratosphere.nephele.configuration.ConfigConstants;
import eu.stratosphere.nephele.configuration.Configuration;
import eu.stratosphere.nephele.configuration.GlobalConfiguration;
import eu.stratosphere.nephele.ipc.RPC;
import eu.stratosphere.nephele.ipc.RPC.Server;
import eu.stratosphere.pact.testing.DaemonThreadFactory;
import eu.stratosphere.sopremo.execution.ExecutionRequest;
import eu.stratosphere.sopremo.execution.ExecutionResponse;
import eu.stratosphere.sopremo.execution.ExecutionResponse.ExecutionState;
import eu.stratosphere.sopremo.execution.SopremoConstants;
import eu.stratosphere.sopremo.execution.SopremoExecutionProtocol;
import eu.stratosphere.sopremo.execution.SopremoID;

/**
 * @author Arvid Heise
 */
public class SopremoServer implements SopremoExecutionProtocol, Closeable {
	private Server server;

	private Configuration configuration;

	private InetSocketAddress serverAddress, jobManagerAddress;

	private ScheduledExecutorService executorService = createExecutor();

	private Map<SopremoID, SopremoJobInfo> meteorInfo =
		new ConcurrentHashMap<SopremoID, SopremoJobInfo>();

	public SopremoServer() {
		this(new Configuration());
	}

	public SopremoServer(Configuration configuration) {
		this.configuration = configuration;
	}

	/*
	 * (non-Javadoc)
	 * @see java.io.Closeable#close()
	 */
	@Override
	public void close() throws IOException {
		if (this.server != null) {
			this.server.stop();
			this.server = null;
		}
		this.executorService.shutdownNow();
	}

	/*
	 * (non-Javadoc)
	 * @see eu.stratosphere.meteor.execution.SopremoExecutor#execute(eu.stratosphere.meteor.execution.ExecutionRequest)
	 */
	@Override
	public ExecutionResponse execute(ExecutionRequest request) {
		SopremoID jobId = new SopremoID();
		final SopremoJobInfo info = new SopremoJobInfo(request, this.configuration);
		this.meteorInfo.put(jobId, info);
		this.executorService.submit(new SopremoExecutionThread(info, getJobManagerAddress()));
		return this.getState(jobId);
	}

	public InetSocketAddress getJobManagerAddress() {
		InetSocketAddress serverAddress = this.jobManagerAddress;
		if (serverAddress == null) {
			final String address =
				this.configuration.getString(ConfigConstants.JOB_MANAGER_IPC_ADDRESS_KEY, "localhost");
			final int port = this.configuration.getInteger(ConfigConstants.JOB_MANAGER_IPC_PORT_KEY,
				ConfigConstants.DEFAULT_JOB_MANAGER_IPC_PORT);

			serverAddress = new InetSocketAddress(address, port);
		}
		return serverAddress;
	}

	public InetSocketAddress getServerAddress() {
		InetSocketAddress serverAddress = this.serverAddress;
		if (serverAddress == null) {
			final String address = this.configuration.getString(SopremoConstants.SOPREMO_SERVER_IPC_ADDRESS_KEY, null);
			final int port = this.configuration.getInteger(SopremoConstants.SOPREMO_SERVER_IPC_PORT_KEY,
				SopremoConstants.DEFAULT_SOPREMO_SERVER_IPC_PORT);

			serverAddress = new InetSocketAddress(address, port);
		}
		return serverAddress;
	}

	/*
	 * (non-Javadoc)
	 * @see eu.stratosphere.meteor.execution.SopremoExecutor#getStatus(java.lang.String)
	 */
	@Override
	public ExecutionResponse getState(SopremoID jobId) {
		final SopremoJobInfo info = this.meteorInfo.get(jobId);
		if (info == null)
			return new ExecutionResponse(jobId, ExecutionState.ERROR, "Unknown job");
		return new ExecutionResponse(jobId, info.getStatus(), info.getDetail());
	}

	public void start() throws IOException {
		this.startServer();

		Runtime.getRuntime().addShutdownHook(new Thread() {
			/*
			 * (non-Javadoc)
			 * @see java.lang.Thread#run()
			 */
			@Override
			public void run() {
				try {
					close();
				} catch (IOException e) {
				}
			}
		});
	}

	public void setJobManagerAddress(InetSocketAddress jobManagerAddress) {
		if (jobManagerAddress == null)
			throw new NullPointerException("jobManagerAddress must not be null");

		this.jobManagerAddress = jobManagerAddress;
	}

	public void setServerAddress(InetSocketAddress rpcServerAddress) {
		if (rpcServerAddress == null)
			throw new NullPointerException("rpcServerAddress must not be null");

		this.serverAddress = rpcServerAddress;
	}

	private ScheduledThreadPoolExecutor createExecutor() {
		final ScheduledThreadPoolExecutor executor = new ScheduledThreadPoolExecutor(1, new DaemonThreadFactory());
		executor.setMaximumPoolSize(1);
		return executor;
	}

	private void startServer() throws IOException {
		final int handlerCount = GlobalConfiguration.getInteger("jobmanager.rpc.numhandler", 3);
		InetSocketAddress rpcServerAddress = getServerAddress();
		this.server = RPC.getServer(this, rpcServerAddress.getHostName(), rpcServerAddress.getPort(),
			handlerCount);
		this.server.start();
	}
}
