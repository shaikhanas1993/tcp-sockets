package org.example.service;

import static org.example.infrastructure.Logger.LOG;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.HashMap;
import java.util.Map;

import org.example.domain.Event;
import org.example.domain.UserFactory;
import org.example.domain.UserRepository;
import org.example.infrastructure.ConnectionFactory;
import org.example.infrastructure.Logger;

public class Server {
	private final int eventSourcePort;
	private final int clientPort;
	private final UserRepository userRepository;
	private volatile boolean stopRequested = false;

	public Server(int eventSourcePort, int clientPort, boolean debug) {
		this.eventSourcePort = eventSourcePort;
		this.clientPort = clientPort;
		this.userRepository = new UserRepository(new UserFactory(), new ConnectionFactory());
		Logger.DEBUG = debug;
	}

	public void start() {
		startClientsThread();
		LOG.info("Client thread started");
		startEventThread();
		LOG.info("Event thread started");
		shutDownHook();
		LOG.info("Ctrl-C to shut down");
	}

	private void shutDownHook() {
		Runtime.getRuntime().addShutdownHook(new Thread() {
			public void run() {
				LOG.info("Shutting down. Closing all sockets.");
				stopRequested = true;
			}
		});
	}

	private void startEventThread() {
		new Thread("events") {
			@Override
			public void run() {
				Socket eventSource = null;
				try {
					LOG.info("Running. Awaiting event source connection... ");
					eventSource = new ServerSocket(eventSourcePort).accept();
					LOG.info("SUCCESS");
					Map<Long, Event> eventQueue = new HashMap<>();
					Dispatcher dispatcher = new Dispatcher(userRepository);
					InputStream inputStream = eventSource.getInputStream();
					BufferedReader in = new BufferedReader(new InputStreamReader(inputStream));
					while (!stopRequested) {
						String raw = in.readLine();
						if (raw != null) {
							Event event = new Event(raw);
							eventQueue.put(event.sequenceNumber(), event);
						}
						dispatcher.drainQueuedEventsInOrder(eventQueue);
					}
				} catch (IOException e) {
					e.printStackTrace();
				} finally {
					try {
						if (eventSource != null) {
							eventSource.close();
						}
					} catch (IOException e) {
						e.printStackTrace();
					}
				}
			}
		}.start();
	}

	private void startClientsThread() {
		new Thread(new Runnable() {
			@Override
			public void run() {
				try {
					ServerSocket serverSocket = new ServerSocket(clientPort);
					while (!stopRequested) {
						acceptNewClients(serverSocket.accept(), userRepository);
					}
				} catch (IOException e) {
					e.printStackTrace();
				} finally {
					userRepository.disconnectAll();
				}
			}
		}, "clients").start();
	}

	private static void acceptNewClients(Socket clientSocket, UserRepository userRepository) throws IOException {
		BufferedReader in = new BufferedReader(new InputStreamReader(clientSocket.getInputStream()));
		int userId = Integer.parseInt(in.readLine());
		userRepository.connect(userId, clientSocket);
	}
}