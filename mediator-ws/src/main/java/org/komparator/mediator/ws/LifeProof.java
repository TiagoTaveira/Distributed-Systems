package org.komparator.mediator.ws;

import java.util.Timer;
import java.util.TimerTask;

import org.komparator.mediator.ws.cli.MediatorClient;
import org.komparator.mediator.ws.cli.MediatorClientException;

public class LifeProof extends TimerTask {

	@Override
	public void run() {
		MediatorClient client = null;
		if (MediatorEndpointManager.isSecondary == false) {
			try {
				client = new MediatorClient("http://localhost:8072/mediator-ws/endpoint");
				//client.imAlive(); Nao esta a conseguir chamar o metodo
				System.out.println("Im alive");
			} catch (MediatorClientException e) {
				e.printStackTrace();
			}
		}		
	}	
}