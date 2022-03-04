package steam.boiler.core;

import org.eclipse.jdt.annotation.NonNull;
import org.eclipse.jdt.annotation.Nullable;

import steam.boiler.model.SteamBoilerController;
import steam.boiler.util.Mailbox;
import steam.boiler.util.SteamBoilerCharacteristics;
import steam.boiler.util.Mailbox.Message;
import steam.boiler.util.Mailbox.MessageKind;

public class MySteamBoilerController implements SteamBoilerController {

  /**
   * Captures the various modes in which the controller can operate.
   *
   * @author David J. Pearce
   *
   */
  private enum State {
        WAITING, READY, NORMAL, DEGRADED, RESCUE, EMERGENCY_STOP
  }

  /**
   * Records the configuration characteristics for the given boiler problem.
   */
  private final SteamBoilerCharacteristics configuration;

  /**
   * Identifies the current mode in which the controller is operating.
   */
  private State mode = State.WAITING;
  
  /**
   * The target level we want the boiler to be
   * Calculated in the middle of the 2 normal level values
   */
  private final double target;
  
  /**
   * To keep track of whether the valve is currently open
   */
  private boolean valveOpen = false;

  /**
   * Construct a steam boiler controller for a given set of characteristics.
   *
   * @param configuration
   *          The boiler characteristics to be used.
   */
  public MySteamBoilerController(SteamBoilerCharacteristics configuration) {
    this.configuration = configuration;
    target = (configuration.getMaximalNormalLevel() + configuration.getMinimalNormalLevel()) / 2.0;
  }

	/**
	 * This message is displayed in the simulation window, and enables a limited
	 * form of debug output. The content of the message has no material effect on
	 * the system, and can be whatever is desired. In principle, however, it should
	 * display a useful message indicating the current state of the controller.
	 *
	 * @return
	 */
	@Override
	public String getStatusMessage() {
		String val = mode.toString();
		assert val instanceof String;
		return val;
		
	}

	/**
	 * Process a clock signal which occurs every 5 seconds. This requires reading
	 * the set of incoming messages from the physical units and producing a set of
	 * output messages which are sent back to them.
	 *
	 * @param incoming The set of incoming messages from the physical units.
	 * @param outgoing Messages generated during the execution of this method should
	 *                 be written here.
	 */
	@Override
	public void clock(@NonNull Mailbox incoming, @NonNull Mailbox outgoing) {
		// Extract expected messages
		Message levelMessage = extractOnlyMatch(MessageKind.LEVEL_v, incoming);
		Message steamMessage = extractOnlyMatch(MessageKind.STEAM_v, incoming);
		Message[] pumpStateMessages = extractAllMatches(MessageKind.PUMP_STATE_n_b, incoming);
		Message[] pumpControlStateMessages = extractAllMatches(MessageKind.PUMP_CONTROL_STATE_n_b, incoming);
		
		if(levelMessage == null || steamMessage == null) {
			emergencyMode(outgoing);
			return;
		}
		//
		if (transmissionFailure(levelMessage, steamMessage, pumpStateMessages, pumpControlStateMessages)) {
			// Level and steam messages required, so emergency stop.
			emergencyMode(outgoing);
		}
		//

		// FIXME: this is where the main implementation stems from
		switch(mode) {
		case WAITING:
			doWaiting(incoming, outgoing);
			outgoing.send(new Message(MessageKind.MODE_m, Mailbox.Mode.INITIALISATION));
			break;
		case READY:
			doReady(incoming, outgoing);
			break;
		case NORMAL:
			doNormal(incoming, outgoing);
			outgoing.send(new Message(MessageKind.MODE_m, Mailbox.Mode.NORMAL));
			break;
			
		
		}
		// NOTE: this is an example message send to illustrate the syntax
		//outgoing.send(new Message(MessageKind.MODE_m, Mailbox.Mode.INITIALISATION));
	}
	
	public void doNormal(Mailbox incoming, Mailbox outgoing) {
		Message level = extractOnlyMatch(MessageKind.LEVEL_v, incoming);
		Message steam = extractOnlyMatch(MessageKind.STEAM_v, incoming);
		assert level != null && steam != null;
		int number = calcPumps(level, steam);
		turnOnPumps(number, outgoing);
		
	}
	
	public void turnOnPumps(int num, Mailbox outgoing) {
		for(int i = 0; i < configuration.getNumberOfPumps(); i++) {
			if(i < num) {
				outgoing.send(new Message(MessageKind.OPEN_PUMP_n, i));
			} else {
				outgoing.send(new Message(MessageKind.CLOSE_PUMP_n, i));
			}
		}
	}
	
	public int calcPumps(Message level, Message steam) {
		
		int bestNum = 0;
		double bestDist = 9999;
		for(int i = 0; i <= configuration.getNumberOfPumps(); i++) {
//			double last = configuration.getPumpCapacity(0);
//			for(int j = 1; j < i+1; j++) {
//				if(last ) //should check capacity is same for all
//				last = configuration.getPumpCapacity(j);
//			}
			
			double max = level.getDoubleParameter() + (5 * configuration.getPumpCapacity(0) * (i))
					- (5 * steam.getDoubleParameter());
			double min = level.getDoubleParameter() + (5 * configuration.getPumpCapacity(0) * (i))
					- (5 * configuration.getMaximualSteamRate());
			
			if(Math.abs(((max + min) / 2) - target) < bestDist) {
				bestNum = i;
				bestDist = Math.abs(((max + min) / 2) - target);
			}
		}
		return bestNum;
	}
	
	public void doReady(Mailbox incoming, Mailbox outgoing) {
		if(extractAllMatches(MessageKind.PHYSICAL_UNITS_READY, incoming).length == 1) {
			mode = State.NORMAL;
			outgoing.send(new Message(MessageKind.MODE_m, Mailbox.Mode.NORMAL));
		}
	}
	
	public void doWaiting(Mailbox incoming, Mailbox outgoing) {
		
		if(extractAllMatches(MessageKind.STEAM_BOILER_WAITING, incoming).length != 1) {
			return;
		} 
		
		Message levelMessage = extractOnlyMatch(MessageKind.LEVEL_v, incoming);
		Message steamMessage = extractOnlyMatch(MessageKind.STEAM_v, incoming);
		if(levelMessage == null || steamMessage == null) {
			emergencyMode(outgoing);
		} else if(steamMessage.getDoubleParameter() != 0){
			emergencyMode(outgoing);
		} else if(levelMessage.getDoubleParameter() < 0 || levelMessage.getDoubleParameter() >= configuration.getCapacity()) { 
			System.out.println("sensor broken");
			emergencyMode(outgoing);
		} else if(levelMessage.getDoubleParameter() > configuration.getMaximalNormalLevel()
				|| levelMessage.getDoubleParameter() < configuration.getMinimalNormalLevel()) {
			hitInitialTarget(levelMessage, steamMessage, outgoing);
		} else {
			outgoing.send(new Message(MessageKind.PROGRAM_READY));
			this.mode = State.READY;
		}
		
	}
	
	private void emergencyMode(Mailbox outgoing) {
		this.mode = State.EMERGENCY_STOP;
		outgoing.send(new Message(MessageKind.MODE_m, Mailbox.Mode.EMERGENCY_STOP));
	}

	private void hitInitialTarget(Message level, Message steam, Mailbox outgoing) {
		if(level.getDoubleParameter() > configuration.getMaximalNormalLevel() && !valveOpen) {
			outgoing.send(new Message(MessageKind.VALVE));
			valveOpen = true;
		} else if(level.getDoubleParameter() < configuration.getMinimalNormalLevel()) {
			turnOnPumps(calcPumps(level, steam), outgoing);
			if(valveOpen) {
				outgoing.send(new Message(MessageKind.VALVE));
				valveOpen = false;
			}
		} else {
			outgoing.send(new Message(MessageKind.CLOSE_PUMP_n, 0));
			if(valveOpen) {
				outgoing.send(new Message(MessageKind.VALVE));
				valveOpen = true;
			}
		}
	}
	
	
	/**
	 * Check whether there was a transmission failure. This is indicated in several
	 * ways. Firstly, when one of the required messages is missing. Secondly, when
	 * the values returned in the messages are nonsensical.
	 *
	 * @param levelMessage      Extracted LEVEL_v message.
	 * @param steamMessage      Extracted STEAM_v message.
	 * @param pumpStates        Extracted PUMP_STATE_n_b messages.
	 * @param pumpControlStates Extracted PUMP_CONTROL_STATE_n_b messages.
	 * @return
	 */
	private boolean transmissionFailure(Message levelMessage, Message steamMessage, Message[] pumpStates,
			Message[] pumpControlStates) {
		// Check level readings
		if (pumpStates.length != configuration.getNumberOfPumps()) {
			// Nonsense pump state readings
			return true;
		} else if (pumpControlStates.length != configuration.getNumberOfPumps()) {
			// Nonsense pump control state readings
			return true;
		}
		// Done
		return false;
	}

	/**
	 * Find and extract a message of a given kind in a mailbox. This must the only
	 * match in the mailbox, else <code>null</code> is returned.
	 *
	 * @param kind     The kind of message to look for.
	 * @param incoming The mailbox to search through.
	 * @return The matching message, or <code>null</code> if there was not exactly
	 *         one match.
	 */
	private static @Nullable Message extractOnlyMatch(MessageKind kind, Mailbox incoming) {
		Message match = null;
		for (int i = 0; i != incoming.size(); ++i) {
			Message ith = incoming.read(i);
			if (ith.getKind() == kind) {
				if (match == null) {
					match = ith;
				} else {
					// This indicates that we matched more than one message of the given kind.
					return null;
				}
			}
		}
		return match;
	}

	/**
	 * Find and extract all messages of a given kind.
	 *
	 * @param kind     The kind of message to look for.
	 * @param incoming The mailbox to search through.
	 * @return The array of matches, which can empty if there were none.
	 */
	private static Message[] extractAllMatches(MessageKind kind, Mailbox incoming) {
		int count = 0;
		// Count the number of matches
		for (int i = 0; i != incoming.size(); ++i) {
			Message ith = incoming.read(i);
			if (ith.getKind() == kind) {
				count = count + 1;
			}
		}
		// Now, construct resulting array
		Message[] matches = new Message[count];
		int index = 0;
		for (int i = 0; i != incoming.size(); ++i) {
			Message ith = incoming.read(i);
			if (ith.getKind() == kind) {
				matches[index++] = ith;
			}
		}
		return matches;
	}
}
