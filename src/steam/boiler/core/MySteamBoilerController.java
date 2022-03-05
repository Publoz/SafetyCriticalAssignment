package steam.boiler.core;

import java.util.Arrays;

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
  
  private final boolean[] pumpsState;
  
  private final double[] expectedRange;
  
  private double lastSteam = 0;
  
  private final int[] brokenParts;
  
  
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
    pumpsState = new boolean[configuration.getNumberOfPumps()];
    for(int i = 0; i < configuration.getNumberOfPumps(); i++) {
    	pumpsState[i] = false;
    }
    expectedRange = new double[2];
    expectedRange[0] = -1;
    expectedRange[1] = -1;
    
    //pumps + controllers + steam + level + valve
    //Order - Valve[0] Steam[1] Level[2], Pumps[...] Controllers[...]
    //0 = working fine, 1 = StuckOn, 2 = StuckOff, 3 = Not working at right level
    //			11 = Acknowledged, 12 = Acknowledged
    brokenParts = new int[(configuration.getNumberOfPumps()*2) + 3]; 
    for(int i = 0; i < brokenParts.length; i++) {
    	brokenParts[i] = 0;
    }
    
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
		
		//System.out.println("Clock");
		
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
		
		//checkSteamAndWater(levelMessage, steamMessage, outgoing);
		//checkPumps(pumpStateMessages, pumpControlStateMessages, outgoing);
		//checkAcks(extractAllAcks(incoming)); //Doesnt seem to happen
		checkRepairs(extractAllRepairs(incoming), outgoing);
		
		checkFailures(levelMessage, steamMessage, pumpStateMessages, pumpControlStateMessages, outgoing);
		
		//System.out.println("level" + levelMessage.getDoubleParameter());
		//System.out.println(getStatusMessage());
		// FIXME: this is where the main implementation stems from
		switch(mode) {
		case WAITING:
			doWaiting(incoming, outgoing);
			//outgoing.send(new Message(MessageKind.MODE_m, Mailbox.Mode.INITIALISATION));
			break;
		case READY:
			doReady(incoming, outgoing);
			break;
		case NORMAL:
			doNormal(incoming, outgoing);
			//outgoing.send(new Message(MessageKind.MODE_m, Mailbox.Mode.NORMAL));
			break;
		case DEGRADED:
			doDegraded(incoming, outgoing);
			//outgoing.send(new Message(MessageKind.MODE_m, Mailbox.Mode.DEGRADED));
			break;
		case RESCUE:
			
			//outgoing.send(new Message(MessageKind.MODE_m, Mailbox.Mode.RESCUE));
			break;
		}
		
		switch(mode) {
		case WAITING:
			//doWaiting(incoming, outgoing);
			outgoing.send(new Message(MessageKind.MODE_m, Mailbox.Mode.INITIALISATION));
			break;
		case READY:
			outgoing.send(new Message(MessageKind.MODE_m, Mailbox.Mode.INITIALISATION));
			//doReady(incoming, outgoing);
			break;
		case NORMAL:
			//doNormal(incoming, outgoing);
			outgoing.send(new Message(MessageKind.MODE_m, Mailbox.Mode.NORMAL));
			break;
		case DEGRADED:
			//doDegraded(incoming, outgoing);
			outgoing.send(new Message(MessageKind.MODE_m, Mailbox.Mode.DEGRADED));
			break;
		case RESCUE:
			
			outgoing.send(new Message(MessageKind.MODE_m, Mailbox.Mode.RESCUE));
			break;
		}
		
		// NOTE: this is an example message send to illustrate the syntax
		//outgoing.send(new Message(MessageKind.MODE_m, Mailbox.Mode.INITIALISATION));
	}
	
	private void checkAcks(Message[] acks) {
		for(int i = 0; i < acks.length; i++) {
			if(mode == State.NORMAL) {
				System.out.println("Ack message in normal");
				return;
			}
			
			
			if(acks[i].getKind() == MessageKind.LEVEL_FAILURE_ACKNOWLEDGEMENT) {
				if(brokenParts[2] == 1) {
					brokenParts[2] = 11;
				} else {
					System.out.println("false ack of Level");
				}
			} else if(acks[i].getKind() == MessageKind.STEAM_OUTCOME_FAILURE_ACKNOWLEDGEMENT) {
				if(brokenParts[1] == 1) {
					brokenParts[1] = 11;
				} else {
					System.out.println("false ack of Steam");
				}
			} else if(acks[i].getKind() == MessageKind.PUMP_CONTROL_FAILURE_ACKNOWLEDGEMENT_n) {
				if(brokenParts[3+configuration.getNumberOfPumps()+acks[i].getIntegerParameter()]== 1) {
					brokenParts[3+configuration.getNumberOfPumps()+acks[i].getIntegerParameter()] = 11;
				} else {
					System.out.println("false ack of Controller " + acks[i].getIntegerParameter());
				}
			} else {
				int val = brokenParts[3+acks[i].getIntegerParameter()];
				if(val == 1 || val == 2 || val == 3) {
					brokenParts[3+acks[i].getIntegerParameter()] = val + 10;
				} else {
					if(val < 10) {
						System.out.println("false ack of Pump " + acks[i].getIntegerParameter());
					}
					
				}
			}
		}
	}
	
	/**
	 * Checks all repair messages
	 * 
	 * Code 11, 12 & 13 signal the type of fault and that they'd been
	 * previously acknowledged
	 * 
	 * @param repairs
	 * @param outgoing
	 */
	private void checkRepairs(Message[] repairs, Mailbox outgoing) {
		for(int i = 0; i < repairs.length; i++) {
			
			if(mode == State.NORMAL) {
				System.out.println("Repair message in normal");
				return;
			}
			
			if(repairs[i].getKind() == MessageKind.LEVEL_REPAIRED) {
				if(brokenParts[2] == 11 || brokenParts[2] == 1) {
					outgoing.send((new Message(MessageKind.LEVEL_REPAIRED_ACKNOWLEDGEMENT)));
					brokenParts[2] = 0;
					
				} else {
					System.out.println("Rogue repair of level");
				}
			} else if(repairs[i].getKind() == MessageKind.STEAM_REPAIRED) {
				if(brokenParts[1] == 11 || brokenParts[1] == 11) {
					outgoing.send((new Message(MessageKind.STEAM_REPAIRED_ACKNOWLEDGEMENT)));
					brokenParts[1] = 0;
					
				} else {
					System.out.println("Rogue repair of STEAM");
				}
			} else if(repairs[i].getKind() == MessageKind.PUMP_CONTROL_REPAIRED_n) {
				if(brokenParts[3+configuration.getNumberOfPumps()+repairs[i].getIntegerParameter()] == 11
						|| brokenParts[3+configuration.getNumberOfPumps()+repairs[i].getIntegerParameter()] == 1) {
					outgoing.send((new Message(MessageKind.PUMP_CONTROL_REPAIRED_ACKNOWLEDGEMENT_n, 
							repairs[i].getIntegerParameter())));
					brokenParts[3+configuration.getNumberOfPumps()+repairs[i].getIntegerParameter()] = 0;
				} else {
					System.out.println("Rogue repair of Controller " + repairs[i].getIntegerParameter());
				}
			} else {
				if(brokenParts[3+repairs[i].getIntegerParameter()] == 11
						|| brokenParts[3+repairs[i].getIntegerParameter()] == 12
						|| brokenParts[3+repairs[i].getIntegerParameter()] == 13
						|| brokenParts[3+repairs[i].getIntegerParameter()] == 1
						|| brokenParts[3+repairs[i].getIntegerParameter()] == 2
						|| brokenParts[3+repairs[i].getIntegerParameter()] == 3) {
					outgoing.send((new Message(MessageKind.PUMP_REPAIRED_ACKNOWLEDGEMENT_n, 
							repairs[i].getIntegerParameter())));
					brokenParts[3+repairs[i].getIntegerParameter()] = 0;
				} else {
					System.out.println("Rogue repair of Pump " + repairs[i].getIntegerParameter());
				}
			}
		}
		if(repairs.length > 0) {
			selectMode();
		}
	}
	
	/**
	 * Determine whether we need to go into rescue, normal or degraded mode
	 * based on previously found errors
	 */
	private void selectMode() {
		
		for(int i = 0; i < brokenParts.length; i++) {
			if(brokenParts[i] != 0) {
				if(i == 2) {
					mode = State.RESCUE;
				} else {
					mode = State.DEGRADED;
				}
				return;
			}
		}
		//System.out.println("Normal");
		mode = State.NORMAL;
	}
	
	private void checkFailures(Message level, Message steam, Message[] pumps, Message[] controls,
			Mailbox outgoing) {
		double steamVal = steam.getDoubleParameter();
		if(steamVal < 0 || steamVal > configuration.getMaximualSteamRate()
				|| lastSteam > steamVal) { //Check for obvious steam first
			mode = State.DEGRADED;
			outgoing.send(new Message(MessageKind.STEAM_FAILURE_DETECTION));
			return;
		}
		
		
		for(int i = 0; i < configuration.getNumberOfPumps(); i++) {
			Message pump = pumps[i];
			Message control = controls[i];
			assert pump instanceof Message && control instanceof Message;
			int result = checkPumps(pump, control, level.getDoubleParameter(), i);
			if(result != 0) {
				mode = State.DEGRADED;
				if(result == 1) {
					//System.out.println("Pump failure " + i);
					outgoing.send(new Message(MessageKind.PUMP_FAILURE_DETECTION_n, i));
					break; //assume 1 error
				} else {
					System.out.println("Control failure");
					outgoing.send(new Message(MessageKind.PUMP_CONTROL_FAILURE_DETECTION_n, i));
					break; //assume 1 error
				}
			}
			
			//on last iteration, nothing is wrong with pumps so check water level is fine
			if(i == configuration.getNumberOfPumps() - 1) { 
				if(waterLevelNormal(level.getDoubleParameter()) == false) {
					outgoing.send(new Message(MessageKind.LEVEL_FAILURE_DETECTION));
					mode = State.RESCUE;
					brokenParts[2] = 1;
					
				}
			}
		}
		
	}
	
	private boolean waterLevelNormal(double levelVal) {
		if(expectedRange[0] == -1 || expectedRange[1] == -1) {
			return true;
		}
		if(levelVal < expectedRange[0] || levelVal > expectedRange[1]){
			//levelVal <= configuration.getMinimalLimitLevel() || levelVal >= configuration.getMaximalLimitLevel() {
			return false;
		}
		return true;
	}
	
	/**
	 * Check if a given pump and controller is working correctly
	 */
	private int checkPumps(Message pumpMessage, Message controllerMessage, double level, int i) {
		
		boolean pump = pumpMessage.getBooleanParameter();
		boolean control = controllerMessage.getBooleanParameter();
		if(pump != control || pump != pumpsState[i] || control != pumpsState[i]){ //error
				boolean waterLevelNormal = waterLevelNormal(level);
				if(control == pumpsState[i] && waterLevelNormal) { //Pump wrong 1
					//pump not responding correctly - ie telling us the opposite
					return 1;
				} else if(control == pumpsState[i] && !waterLevelNormal(level)) { //pump and level wrong 2
					//Pump failure
					return 1;
				} else if(pump == control && control != pumpsState[i] && waterLevelNormal) { //pump and control wrong 3
					//pump failure
					brokenParts[3+i] = 2;
					pumpsState[i] = !pumpsState[i]; //have to update now that pump is doing opposite
					return 1;
				} else if(control != pump && pump == pumpsState[i] && !waterLevelNormal) { //control and level wrong 4
					//pump failure
					if(level  > expectedRange[1]) { //stuck on
						brokenParts[3+i] = 1; 
						System.out.println("Stuck on");
						pumpsState[i] = true;
					} else { //stuck off
						brokenParts[3+i] = 2;
						System.out.println("Stuck off");
						pumpsState[i] = true;
					}
					return 1;
				} else if(control != pumpsState[i] && pump == pumpsState[i] && waterLevelNormal) { //control wrong 5
					//Likely control failure
					return 2; //could be 1
				} else if(control != pumpsState[i] && pump != pumpsState[i] && !waterLevelNormal) { //pump, control & level 6
					//pump failure
					brokenParts[3+i] = 2;
					return 1;
					
				}
		}
		return 0;
	}
	
	public void doDegraded(Mailbox incoming, Mailbox outgoing) {
		Message level = extractOnlyMatch(MessageKind.LEVEL_v, incoming);
		Message steam = extractOnlyMatch(MessageKind.STEAM_v, incoming);
		assert level != null && steam != null;
		
		int num = calcPumps(level, steam);
		//System.out.println("We think " + num);
		
		int on = 0;
		for(int i = 0; i < configuration.getNumberOfPumps(); i++) { //find out how many locked on
			if(on == num) {
				break;
			}
			
			int pumpStatus = brokenParts[3+i];
			if(pumpStatus == 1 || pumpStatus == 11) {
				on++;
				assert pumpsState[i] == true;
			}
		}
		if(on != num) {
			for(int i = 0; i < configuration.getNumberOfPumps(); i++) { //if we need more pumps on, turn on non-faulty
				if(on == num) {
					outgoing.send(new Message(MessageKind.CLOSE_PUMP_n, i));
					pumpsState[i] = false;
					continue;
				}
				int pumpStatus = brokenParts[3+i];
				if(pumpStatus == 0) {
					outgoing.send(new Message(MessageKind.OPEN_PUMP_n, i));
					pumpsState[i] = true;
					on++;
				}
			}
		} else {
			for(int i = 0; i < configuration.getNumberOfPumps(); i++) {
				if(brokenParts[3+i] == 0) {
					outgoing.send(new Message(MessageKind.CLOSE_PUMP_n, i));
					pumpsState[i] = false;
				}
			}
		}
		
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
				pumpsState[i] = true;
			} else {
				outgoing.send(new Message(MessageKind.CLOSE_PUMP_n, i));
				pumpsState[i] = false;
			}
		}
	}
	
	private int pumpsLockedOn() {
		int count = 0;
		for(int i = 3; i < 3+configuration.getNumberOfPumps(); i++) {
			if(brokenParts[i] == 1 || brokenParts[i] == 11) {
				count++;
			}
		}
		return count;
	}
	
	private int pumpsLockedOff() {
		int count = 0;
		for(int i = 3; i < 3+configuration.getNumberOfPumps(); i++) {
			if(brokenParts[i] == 2 || brokenParts[i] == 12) {
				count++;
			}
		}
		return count;
	}
	
	public int calcPumps(Message level, Message steam) {
		
		int bestNum = 0;
		double bestDist = 9999;
		
		for(int i = 0 + pumpsLockedOn(); i <= configuration.getNumberOfPumps()-pumpsLockedOff(); i++) {
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
				expectedRange[0] = min - 0.0001;
				expectedRange[1] = max + 0.0001; //add slight offset otherwise too sensitive
			}
		}
		//System.out.println(expectedRange[0]);
		//System.out.println(expectedRange[1]);
		
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
			closeAllPumps(outgoing);
			if(valveOpen) {
				outgoing.send(new Message(MessageKind.VALVE));
				valveOpen = false;
			}
			outgoing.send(new Message(MessageKind.PROGRAM_READY));
			this.mode = State.READY;
			
		}
		
	}
	
	private void emergencyMode(Mailbox outgoing) {
		this.mode = State.EMERGENCY_STOP;
		outgoing.send(new Message(MessageKind.MODE_m, Mailbox.Mode.EMERGENCY_STOP));
	}
	
	private void initialFill(Message levelMessage, Mailbox outgoing) {
		int best = 1;
		double dist = 9999;
		
		
		for(int i = 1; i <= configuration.getNumberOfPumps(); i++) {
			double level = levelMessage.getDoubleParameter() + (5 * configuration.getPumpCapacity(0) * i);
			if(Math.abs(level - target) < dist) {
				best = i;
				dist = Math.abs(level - target);
				expectedRange[0] = level;
			}
		}
		
		for(int i = 0; i < configuration.getNumberOfPumps(); i++) {
			if(i < best) {
				outgoing.send(new Message(MessageKind.OPEN_PUMP_n, i));
				pumpsState[i] = true;
			} else {
				outgoing.send(new Message(MessageKind.CLOSE_PUMP_n, i));
				pumpsState[i] = false;
			}
		}
		
	}
	
	private void closeAllPumps(Mailbox outgoing) {
		for(int i = 0; i < configuration.getNumberOfPumps(); i++) {
			outgoing.send(new Message(MessageKind.CLOSE_PUMP_n, i));
			pumpsState[i] = false;
		}
	}

	private void hitInitialTarget(Message level, Message steam, Mailbox outgoing) {
		if(level.getDoubleParameter() > configuration.getMaximalNormalLevel() && !valveOpen) {
			outgoing.send(new Message(MessageKind.VALVE));
			valveOpen = true;
		} else if(level.getDoubleParameter() < configuration.getMinimalNormalLevel()) {
			//turnOnPumps(calcPumps(level, steam), outgoing);
			if(expectedRange[0] != -1 && expectedRange[0] != level.getDoubleParameter()) { //something is not working
				//trigger degrade mode once done //FIXME
			}
			initialFill(level, outgoing);
			if(valveOpen) {
				outgoing.send(new Message(MessageKind.VALVE));
				valveOpen = false;
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
	
	/**
	 * Extract all failure acknowledgments
	 * 
	 * @param incoming
	 * @return
	 */
	private static Message[] extractAllAcks(Mailbox incoming) {
		MessageKind[] repairs = {MessageKind.LEVEL_FAILURE_ACKNOWLEDGEMENT, MessageKind.PUMP_FAILURE_ACKNOWLEDGEMENT_n,
				MessageKind.PUMP_FAILURE_ACKNOWLEDGEMENT_n, MessageKind.STEAM_OUTCOME_FAILURE_ACKNOWLEDGEMENT};
		int count = 0;
		// Count the number of matches
		for (int i = 0; i != incoming.size(); ++i) {
			Message ith = incoming.read(i);
			if (Arrays.stream(repairs).anyMatch(ith.getKind()::equals)) {
				count = count + 1;
			}
		}
		// Now, construct resulting array
		Message[] matches = new Message[count];
		int index = 0;
		for (int i = 0; i != incoming.size(); ++i) {
			Message ith = incoming.read(i);
			if (Arrays.stream(repairs).anyMatch(ith.getKind()::equals)) {
				matches[index++] = ith;
			}
		}
		return matches;
	}
	
	/**
	 * Find and extract all messages that are repairs.
	 *
	 * @param kind     The kind of message to look for.
	 * @param incoming The mailbox to search through.
	 * @return The array of matches, which can empty if there were none.
	 */
	private static Message[] extractAllRepairs(Mailbox incoming) {
		MessageKind[] repairs = {MessageKind.LEVEL_REPAIRED, MessageKind.PUMP_CONTROL_REPAIRED_n,
				MessageKind.PUMP_REPAIRED_n, MessageKind.STEAM_REPAIRED};
		int count = 0;
		// Count the number of matches
		for (int i = 0; i != incoming.size(); ++i) {
			Message ith = incoming.read(i);
			if (Arrays.stream(repairs).anyMatch(ith.getKind()::equals)) {
				count = count + 1;
			}
		}
		// Now, construct resulting array
		Message[] matches = new Message[count];
		int index = 0;
		for (int i = 0; i != incoming.size(); ++i) {
			Message ith = incoming.read(i);
			if (Arrays.stream(repairs).anyMatch(ith.getKind()::equals)) {
				matches[index++] = ith;
			}
		}
		return matches;
	}
}
