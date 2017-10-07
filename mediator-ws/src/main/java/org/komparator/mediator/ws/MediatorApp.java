package org.komparator.mediator.ws;

import java.util.Timer;

public class MediatorApp {

	public static void main(String[] args) throws Exception {
		Timer timer = new Timer(true);
		LifeProof lifeProof = new LifeProof();
		long firstTime = 0 * 1000;
		long period = 5 * 1000;
		
		// Check arguments
		if (args.length == 0 || args.length == 2) {
			System.err.println("Argument(s) missing!");
			System.err.println("Usage: java " + MediatorApp.class.getName() + " wsURL OR uddiURL wsName wsURL");
			return;
		}
		String uddiURL = null;
		String wsName = null;
		String wsURL = null;

		// Create server implementation object, according to options
		MediatorEndpointManager endpoint = null;
		if (args.length == 1) {
			wsURL = args[0];
			endpoint = new MediatorEndpointManager(wsURL);
		} else if (args.length >= 3) {
			uddiURL = args[0];
			wsName = args[1];
			wsURL = args[2];
			endpoint = new MediatorEndpointManager(uddiURL, wsName, wsURL);
			endpoint.setVerbose(true);
		}

		try {
			timer.schedule(lifeProof, firstTime, period);
			endpoint.start();
			endpoint.awaitConnections();
		} finally {
			endpoint.stop();
		}

	}

}
