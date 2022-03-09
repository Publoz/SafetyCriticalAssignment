package steam.boiler.core;

import java.util.Arrays;
import org.eclipse.jdt.annotation.NonNull;
import org.eclipse.jdt.annotation.Nullable;
import steam.boiler.model.SteamBoilerController;
import steam.boiler.util.Mailbox;
import steam.boiler.util.Mailbox.Message;
import steam.boiler.util.Mailbox.MessageKind;
import steam.boiler.util.SteamBoilerCharacteristics;

/**
 * Steam boiler controller class.
 * 
 * <p>controls a steam boiler and handles faults with the hardware

 * @author Paul Ireland
 */
public class MySteamBoilerController implements SteamBoilerController {

  /**
   * Captures the various modes in which the controller can operate.
   *
   * @author David J. Pearce
   *
   */
  private enum State {
    /**
     * Initial state where we wait for the physical units to tell us they are ready.
     */
    WAITING,
    /**
     * State where we wait for the units to tell us they are ready to begin normal
     * operation.
     */
    READY,
    /**
     * The state where the boiler operates normally.
     */
    NORMAL,
    /**
     * The state where we have detected something is wrong.
     */
    DEGRADED,
    /**
     * The state where we detected the level sensor is broken.
     */
    RESCUE,
    /**
     * The state where we have a serious failure and have to stop.
     */
    EMERGENCY_STOP
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
   * The target level we want the boiler to be Calculated in the middle of the 2
   * normal level values.
   */
  private final double target;

  /**
   * A boolean array that shows what we expect each pump to be doing ie open or
   * closed.
   */
  private final boolean[] pumpsState;

  /**
   * A 2 long double array that has the min and max of where the water level
   * should be on the next cycle.
   */
  private final double[] expectedRange;

  /**
   * What the reading of the last steam sensor was.
   */
  private double lastSteam = 0;

  /**
   * An int array that records what parts are broken and what type of failure it
   * is.
   */
  private final int[] brokenParts;

  private boolean test = false;

  /**
   * To keep track of whether the valve is currently open.
   */
  private boolean valveOpen = false;

  /**
   * Construct a steam boiler controller for a given set of characteristics.
   *
   * @param configuration The boiler characteristics to be used.
   */
  public MySteamBoilerController(SteamBoilerCharacteristics configuration) {
    this.configuration = configuration;
    this.target = (configuration.getMaximalNormalLevel() + configuration.getMinimalNormalLevel())
        / 2.0;
    this.pumpsState = new boolean[configuration.getNumberOfPumps()];
    for (int i = 0; i < configuration.getNumberOfPumps(); i++) {
      this.pumpsState[i] = false;
    }
    this.expectedRange = new double[2];
    this.expectedRange[0] = -1;
    this.expectedRange[1] = -1;

    // pumps + controllers + steam + level + valve
    // Order - Valve[0] Steam[1] Level[2], Pumps[...] Controllers[...]
    // 0 = working fine, 1 = StuckOn, 2 = StuckOff, 3 = Not working at right level,
    // 4 = transmit wrong
    // 11 = Acknowledged, 12 = Acknowledged
    this.brokenParts = new int[(configuration.getNumberOfPumps() * 2) + 3];
    for (int i = 0; i < this.brokenParts.length; i++) {
      this.brokenParts[i] = 0;
    }

  }

  /**
   * This message is displayed in the simulation window, and enables a limited
   * form of debug output. The content of the message has no material effect on
   * the system, and can be whatever is desired. In principle, however, it should
   * display a useful message indicating the current state of the controller.
   *
   * @return the status message
   */
  @Override
  public String getStatusMessage() {
    String val = this.mode.toString();
    assert val != null;
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
    Message[] pumpControlStateMessages = extractAllMatches(MessageKind.PUMP_CONTROL_STATE_n_b,
        incoming);

    // System.out.println("Clock");

    if (levelMessage == null || steamMessage == null) {
      emergencyMode(outgoing);
      return;
    }
    //
    if (transmissionFailure(levelMessage, steamMessage, pumpStateMessages,
        pumpControlStateMessages)) {
      // Level and steam messages required, so emergency stop.
      emergencyMode(outgoing);
      return;
    }

    if (this.test) {
      System.out.println("######"); //$NON-NLS-1$
      System.out.println(this.expectedRange[0]);
      System.out.println(levelMessage.getDoubleParameter());
      System.out.println(this.expectedRange[1]);
      this.test = false;
    }
    //

    // checkSteamAndWater(levelMessage, steamMessage, outgoing);
    // checkPumps(pumpStateMessages, pumpControlStateMessages, outgoing);
    // checkAcks(extractAllAcks(incoming)); //Doesnt seem to happen
    // System.out.println("------");
    // System.out.println(expectedRange[0]);
    // System.out.println(levelMessage.getDoubleParameter());
    // System.out.println(expectedRange[1]);

    if (this.mode != State.WAITING && this.mode != State.READY) {
      checkFailures(levelMessage, steamMessage, pumpStateMessages, pumpControlStateMessages,
          outgoing);
      if (this.mode == State.DEGRADED || this.mode == State.RESCUE) {
        checkRepairs(extractAllRepairs(incoming), outgoing);
      }

    }
    // System.out.println("level" + levelMessage.getDoubleParameter());
    // System.out.println(getStatusMessage());
    // FIXME: this is where the main implementation stems from
    switch (this.mode) {
      case WAITING:
        doWaiting(incoming, outgoing);
        // outgoing.send(new Message(MessageKind.MODE_m, Mailbox.Mode.INITIALISATION));
        break;
      case READY:
        doReady(incoming, outgoing);
        break;
      case NORMAL:
        doNormal(incoming, outgoing);
        // outgoing.send(new Message(MessageKind.MODE_m, Mailbox.Mode.NORMAL));
        break;
      case DEGRADED:
        doDegraded(incoming, outgoing);
        // outgoing.send(new Message(MessageKind.MODE_m, Mailbox.Mode.DEGRADED));
        break;
      case RESCUE:
        doDegraded(incoming, outgoing);
        // outgoing.send(new Message(MessageKind.MODE_m, Mailbox.Mode.RESCUE));
        break;
      default:
        assert false;
    }

    // Check in next cycle we aren't going to go past a limit
    if (this.mode != State.WAITING && this.mode != State.READY) {
      checkLimitLevels(levelMessage, steamMessage, outgoing);
    }

    switch (this.mode) {
      case WAITING:
        // doWaiting(incoming, outgoing);
        outgoing.send(new Message(MessageKind.MODE_m, Mailbox.Mode.INITIALISATION));
        break;
      case READY:
        outgoing.send(new Message(MessageKind.MODE_m, Mailbox.Mode.INITIALISATION));
        // doReady(incoming, outgoing);
        break;
      case NORMAL:
        // doNormal(incoming, outgoing);
        outgoing.send(new Message(MessageKind.MODE_m, Mailbox.Mode.NORMAL));
        break;
      case DEGRADED:
        // doDegraded(incoming, outgoing);
        outgoing.send(new Message(MessageKind.MODE_m, Mailbox.Mode.DEGRADED));
        break;
      case RESCUE:
  
        outgoing.send(new Message(MessageKind.MODE_m, Mailbox.Mode.RESCUE));
        break;
      default:
        assert false;
    }

    this.lastSteam = steamMessage.getDoubleParameter();
    // NOTE: this is an example message send to illustrate the syntax
    // outgoing.send(new Message(MessageKind.MODE_m, Mailbox.Mode.INITIALISATION));
  }

  /**
   * Check whether we are approaching one of the limits.

   * @param levelMessage the level message
   * @param steamMessage the steam level message
   * @param outgoing     the outgoing mailbox
   */
  private void checkLimitLevels(Message levelMessage, Message steamMessage, Mailbox outgoing) {

    if (this.expectedRange[0] == -1) {
      return;
    }

    if (this.expectedRange[1] > this.configuration.getMaximalLimitLevel()
        || this.expectedRange[0] < this.configuration.getMinimalLimitLevel()) {
      emergencyMode(outgoing);
    }

  }

  /**
   * Check our inbox for the physical units acknowleding our failures.
   * 
   * @param acks the array of acks we need to check
   */
//  private void checkAcks(Message[] acks) {
//    for (int i = 0; i < acks.length; i++) {
//      if (mode == State.NORMAL) {
//        System.out.println("Ack message in normal");
//        return;
//      }
//
//      if (acks[i].getKind() == MessageKind.LEVEL_FAILURE_ACKNOWLEDGEMENT) {
//        if (brokenParts[2] == 1) {
//          brokenParts[2] = 11;
//        } else {
//          System.out.println("false ack of Level");
//        }
//      } else if (acks[i].getKind() == MessageKind.STEAM_OUTCOME_FAILURE_ACKNOWLEDGEMENT) {
//        if (brokenParts[1] == 1) {
//          brokenParts[1] = 11;
//        } else {
//          System.out.println("false ack of Steam");
//        }
//      } else if (acks[i].getKind() == MessageKind.PUMP_CONTROL_FAILURE_ACKNOWLEDGEMENT_n) {
//        if (brokenParts[3 + configuration.getNumberOfPumps()
//            + acks[i].getIntegerParameter()] == 1) {
//          brokenParts[3 + configuration.getNumberOfPumps() + acks[i].getIntegerParameter()] = 11;
//        } else {
//          System.out.println("false ack of Controller " + acks[i].getIntegerParameter());
//        }
//      } else {
//        int val = brokenParts[3 + acks[i].getIntegerParameter()];
//        if (val == 1 || val == 2 || val == 3) {
//          brokenParts[3 + acks[i].getIntegerParameter()] = val + 10;
//        } else {
//          if (val < 10) {
//            System.out.println("false ack of Pump " + acks[i].getIntegerParameter());
//          }
//
//        }
//      }
//    }
//  }

  /**
   * Checks all repair messages.
   *
   * <p>Code 11, 12 & 13 signal the type of fault and that they'd been previously
   * acknowledged

   * @param repairs  an array of repair messages
   * @param outgoing the outgoing mailbox
   */
  private void checkRepairs(Message[] repairs, Mailbox outgoing) {
    for (int i = 0; i < repairs.length; i++) {

      if (this.mode == State.NORMAL) {
        System.out.println("Repair message in normal"); //$NON-NLS-1$
        return;
      }

      if (repairs[i].getKind() == MessageKind.LEVEL_REPAIRED) {
        if (this.brokenParts[2] == 11 || this.brokenParts[2] == 1) {
          outgoing.send((new Message(MessageKind.LEVEL_REPAIRED_ACKNOWLEDGEMENT)));
          this.brokenParts[2] = 0;

        } else {
          System.out.println("Rogue repair of level"); //$NON-NLS-1$
        }
      } else if (repairs[i].getKind() == MessageKind.STEAM_REPAIRED) {
        if (this.brokenParts[1] == 11 || this.brokenParts[1] == 11) {
          outgoing.send((new Message(MessageKind.STEAM_REPAIRED_ACKNOWLEDGEMENT)));
          this.brokenParts[1] = 0;

        } else {
          System.out.println("Rogue repair of STEAM");
        }
      } else if (repairs[i].getKind() == MessageKind.PUMP_CONTROL_REPAIRED_n) {
        if (this.brokenParts[3 + this.configuration.getNumberOfPumps()
            + repairs[i].getIntegerParameter()] == 11
            || this.brokenParts[3 + this.configuration.getNumberOfPumps()
                + repairs[i].getIntegerParameter()] == 1) {
          outgoing.send((new Message(MessageKind.PUMP_CONTROL_REPAIRED_ACKNOWLEDGEMENT_n,
              repairs[i].getIntegerParameter())));
          this.brokenParts[3 + this.configuration.getNumberOfPumps()
              + repairs[i].getIntegerParameter()] = 0;
        } else {
          System.out.println("Rogue repair of Controller " + repairs[i].getIntegerParameter());
        }
      } else {
        if (this.brokenParts[3 + repairs[i].getIntegerParameter()] == 11
            || this.brokenParts[3 + repairs[i].getIntegerParameter()] == 12
            || this.brokenParts[3 + repairs[i].getIntegerParameter()] == 13
            || this.brokenParts[3 + repairs[i].getIntegerParameter()] == 1
            || this.brokenParts[3 + repairs[i].getIntegerParameter()] == 2
            || this.brokenParts[3 + repairs[i].getIntegerParameter()] == 3
            || this.brokenParts[3 + repairs[i].getIntegerParameter()] == 4) {
          outgoing.send((new Message(MessageKind.PUMP_REPAIRED_ACKNOWLEDGEMENT_n,
              repairs[i].getIntegerParameter())));
          brokenParts[3 + repairs[i].getIntegerParameter()] = 0;
        } else {
          System.out.println("Rogue repair of Pump " + repairs[i].getIntegerParameter());
        }
      }
    }
    if (repairs.length > 0) {
      selectMode();
    }
  }

  /**
   * Determine whether we need to go into rescue, normal or degraded mode based on
   * previously found errors.
   */
  private void selectMode() {

    for (int i = 0; i < this.brokenParts.length; i++) {
      if (this.brokenParts[i] != 0) {
        if (i == 2) {
          this.mode = State.RESCUE;
        } else {
          this.mode = State.DEGRADED;
        }
        return;
      }
    }
    // System.out.println("Normal");
    this.mode = State.NORMAL;
  }

  /**
   * Check whether we have encountered any failures.
   *
   * <p>Firstly check for any obvious steam detecting failures. Then check each pump
   * for errors by calling checkPumps(). If we haven't found any issues then
   * finally check the water level sensor. If we find any issues we return since
   * we assume there is at most one failure.

   * @param level    the level message
   * @param steam    the steam level message
   * @param pumps    the array of pump messages
   * @param controls the array of control messages
   * @param outgoing the outgoing mailbox
   */
  private void checkFailures(Message level, Message steam, Message[] pumps, Message[] controls,
      Mailbox outgoing) {
    double steamVal = steam.getDoubleParameter();
    if (steamVal < 0 || steamVal > this.configuration.getMaximualSteamRate()
        || this.lastSteam > steamVal) {
      this.mode = State.DEGRADED;
      outgoing.send(new Message(MessageKind.STEAM_FAILURE_DETECTION));
      return;
    }

    for (int i = 0; i < this.configuration.getNumberOfPumps(); i++) {
      Message pump = pumps[i];
      Message control = controls[i];
      assert pump != null && control != null;
      int result = checkPumps(pump, control, level.getDoubleParameter(), i);
      if (result != 0) {
        this.mode = State.DEGRADED;
        if (result == 1) {
          // System.out.println("Pump failure " + i);
          outgoing.send(new Message(MessageKind.PUMP_FAILURE_DETECTION_n, i));
          break; // assume 1 error
        }
        System.out.println("Control failure");
        System.out.println(this.pumpsState[i]);
        System.out.println(pumps[i].getBooleanParameter());
        System.out.println(controls[i].getBooleanParameter());

        outgoing.send(new Message(MessageKind.PUMP_CONTROL_FAILURE_DETECTION_n, i));
        break; // assume 1 error

      }

      // on last iteration, nothing is wrong with pumps so check water level is fine
      if (i == this.configuration.getNumberOfPumps() - 1) {
        if (waterLevelNormal(level.getDoubleParameter()) == false) {
          outgoing.send(new Message(MessageKind.LEVEL_FAILURE_DETECTION));
          this.mode = State.RESCUE;
          this.brokenParts[2] = 1;
          // System.out.println("detect fail");

        }
      }
    }

  }

  /**
   * Check if the water level is what we expected.

   * @param levelVal the water level
   * @return a boolean whether it is normal
   */
  private boolean waterLevelNormal(double levelVal) {
    if (this.expectedRange[0] == -1 || this.expectedRange[1] == -1) {
      return true;
    }
    if (levelVal < this.expectedRange[0] || levelVal > this.expectedRange[1]) {
      // levelVal <= configuration.getMinimalLimitLevel() || levelVal >=
      // configuration.getMaximalLimitLevel() {
      // System.out.println("========");
      // System.out.println(expectedRange[0]);
      // //System.out.println(configuration.getMinimalLimitLevel());
      // System.out.println(levelVal);
      // System.out.println(expectedRange[1]);
      // System.out.println("========");

      return false;
    }
    return true;
  }

  // Order - Valve[0] Steam[1] Level[2], Pumps[...] Controllers[...]
  // 0 = working fine, 1 = StuckOn, 2 = StuckOff, 3 = Not working at right level,
  // 4 = transmit wrong
  // 11 = Acknowledged, 12 = Acknowledged

  /**
   * Check if a given pump and controller is working correctly.

   * @param pumpMessage       a message from a pump
   * @param controllerMessage a message from the pump's controller
   * @param level             the current water level
   * @param i                 the index of the pump
   * @return an int showing what has failed 0 = none, 1 = pump, 2 = controller
   */
  private int checkPumps(Message pumpMessage, Message controllerMessage, double level, int i) {

    boolean pump = pumpMessage.getBooleanParameter();
    boolean control = controllerMessage.getBooleanParameter();
    if (pump != control || pump != this.pumpsState[i] || control != this.pumpsState[i]) { // error
      boolean waterLevelNormal = waterLevelNormal(level);
      if (control == this.pumpsState[i] && waterLevelNormal) { // Pump wrong 1
        // pump not responding correctly - ie telling us the opposite
        this.brokenParts[3 + i] = 4;
        return 1;
      } else if (control == this.pumpsState[i] && !waterLevelNormal(level)) { 
        // pump and level
        // wrong 2
        // Pump failure
        return 1;
      } else if (pump == control && control != this.pumpsState[i] && waterLevelNormal) { 
        // pump and
        // control wrong
        // 3
        // pump failure
        if (control == true) {
          this.brokenParts[3 + i] = 1;
        } else {
          this.brokenParts[3 + i] = 2;
        }

        this.pumpsState[i] = !this.pumpsState[i]; // have to update now that pump is doing opposite
        return 1;
      } else if (control != pump && pump == this.pumpsState[i] && !waterLevelNormal) { 
        // control
                                                                                       
        // and
        // level wrong 4
        // pump failure
        if (level > this.expectedRange[1]) { // stuck on
          this.brokenParts[3 + i] = 1;
          System.out.println("Stuck on");
          this.pumpsState[i] = true;
        } else { // stuck off
          this.brokenParts[3 + i] = 2;
          System.out.println("Stuck off");
          this.pumpsState[i] = false; // was true
        }
        return 1;
      } else if (control != this.pumpsState[i] && pump == this.pumpsState[i] 
          && waterLevelNormal) { 
        // control
        // wrong 5
        // Likely control failure
        System.out.println("------");
        System.out.println(this.expectedRange[0]);
        System.out.println(level);
        System.out.println(this.expectedRange[1]);
        this.test = true;

        return 2; // could be 1
      } else if (control != this.pumpsState[i] && pump != this.pumpsState[i] 
          && !waterLevelNormal) { // pump,
        // control
        // &
        // level
        // 6
        // pump failure
        this.brokenParts[3 + i] = 2;
        return 1;

      }
    }
    return 0;
  }

  // public void doRescue(Mailbox incoming, Mailbox outgoing) {
  // Message steamMessage = extractOnlyMatch(MessageKind.STEAM_v, incoming);
  // Message level = extractOnlyMatch(MessageKind.LEVEL_v, incoming);
  //
  // double currentMin = expectedRange[0];
  // double currentMax = expectedRange[1];
  //
  // assert level != null && steamMessage != null;
  // int num = calcPumps(level, steamMessage);
  //
  //
  //
  //
  //
  //
  // }

  /**
   * Calculate the pumps we need when in degraded mode.
   *
   * <p>Caluclate how many pumps we need on by calling calcPumps(). Find out how many
   * are locked on. If we need more on then turn on non-faulty. Close all pumps we
   * aren't using

   * @param incoming the incoming mailbox
   * @param outgoing the outgoing mailbox
   */
  public void doDegraded(Mailbox incoming, Mailbox outgoing) {
    Message level = extractOnlyMatch(MessageKind.LEVEL_v, incoming);
    Message steam = extractOnlyMatch(MessageKind.STEAM_v, incoming);
    assert level != null && steam != null;

    int num = calcPumps(level, steam);
    // System.out.println("We think " + num);

    int on = 0;
    for (int i = 0; i < this.configuration.getNumberOfPumps(); i++) { // find out how many locked on
      if (on == num) {
        break;
      }

      int pumpStatus = this.brokenParts[3 + i];
      if (pumpStatus == 1 || pumpStatus == 11) {
        on++;
        assert this.pumpsState[i] == true;
      }
    }
    if (on != num) {
      for (int i = 0; i < this.configuration.getNumberOfPumps(); i++) { 
        // if we need more pumps on,
                                                                        
        // turn
        // on non-faulty
        if (on == num) {
          outgoing.send(new Message(MessageKind.CLOSE_PUMP_n, i));
          this.pumpsState[i] = false;
          continue;
        }
        int pumpStatus = this.brokenParts[3 + i];
        if (pumpStatus == 0 || pumpStatus == 4) {
          outgoing.send(new Message(MessageKind.OPEN_PUMP_n, i));
          this.pumpsState[i] = true;
          on++;
        }
      }
    } else {
      for (int i = 0; i < this.configuration.getNumberOfPumps(); i++) {
        if (this.brokenParts[3 + i] == 0) {
          outgoing.send(new Message(MessageKind.CLOSE_PUMP_n, i));
          this.pumpsState[i] = false;
        }
      }
    }

  }

  /**
   * Find how many pumps we need and turn them on during normal mode.

   * @param incoming the incoming mailbox
   * @param outgoing the outgoing mailbox
   */
  public void doNormal(Mailbox incoming, Mailbox outgoing) {
    Message level = extractOnlyMatch(MessageKind.LEVEL_v, incoming);
    Message steam = extractOnlyMatch(MessageKind.STEAM_v, incoming);
    assert level != null && steam != null;
    int number = calcPumps(level, steam);

    turnOnPumps(number, outgoing);

  }

  /**
   * Turn on a certain amount of pumps assuming everything is normal.

   * @param num      the number to turn on
   * @param outgoing the outgoing mailbox
   */
  public void turnOnPumps(int num, Mailbox outgoing) {
    for (int i = 0; i < this.configuration.getNumberOfPumps(); i++) {
      if (i < num) {
        outgoing.send(new Message(MessageKind.OPEN_PUMP_n, i));
        this.pumpsState[i] = true;
      } else {
        outgoing.send(new Message(MessageKind.CLOSE_PUMP_n, i));
        this.pumpsState[i] = false;
      }
    }
  }

  /**
   * Find out how many pumps are locked on.

   * @return the number locked on
   */
  private int pumpsLockedOn() {
    int count = 0;
    for (int i = 3; i < 3 + this.configuration.getNumberOfPumps(); i++) {
      if (this.brokenParts[i] == 1 || this.brokenParts[i] == 11) {
        count++;
      }
    }
    return count;
  }

  /**
   * Find out how many pumps are locked off.

   * @return the number locked off
   */
  private int pumpsLockedOff() {
    int count = 0;
    for (int i = 3; i < 3 + this.configuration.getNumberOfPumps(); i++) {
      if (this.brokenParts[i] == 2 || this.brokenParts[i] == 12) {
        count++;
      }
    }
    return count;
  }

  /**
   * Calculate how many pumps we need to turn on.
   *
   * <p>Check every number of pumps on for the next cycle and find what gets us
   * closest to the target.

   * @param level the level message
   * @param steam the steam level message
   * @return the number of pumps we should turn on
   */
  public int calcPumps(Message level, Message steam) {

    int bestNum = 0;
    double bestDist = 9999;

    double currentMax = this.expectedRange[1];
    double currentMin = this.expectedRange[0];
    for (int i = 0 + pumpsLockedOn(); i <= this.configuration.getNumberOfPumps()
        - pumpsLockedOff(); i++) {
      // double last = configuration.getPumpCapacity(0);
      // for(int j = 1; j < i+1; j++) {
      // if(last ) //should check capacity is same for all
      // last = configuration.getPumpCapacity(j);
      // }

      double max = 0;
      double min = 0;
      if (this.mode != State.RESCUE) {
        max = level.getDoubleParameter() + (5 * this.configuration.getPumpCapacity(0) * (i))
            - (5 * steam.getDoubleParameter());
        min = level.getDoubleParameter() + (5 * this.configuration.getPumpCapacity(0) * (i))
            - (5 * this.configuration.getMaximualSteamRate());
      } else {
        // If in rescue mode we can't use the level so estimate based on what the
        // current max
        // could be
        max = currentMax + (5 * this.configuration.getPumpCapacity(0) * (i))
            - (5 * steam.getDoubleParameter());
        min = currentMin + (5 * this.configuration.getPumpCapacity(0) * (i))
            - (5 * this.configuration.getMaximualSteamRate());
      }

      if (Math.abs(((max + min) / 2) - this.target) < bestDist) {
        bestNum = i;
        bestDist = Math.abs(((max + min) / 2) - this.target);
        this.expectedRange[0] = min - 0.2001;
        this.expectedRange[1] = max + 0.2001; // add slight offset otherwise too sensitive

      }
    }
    // System.out.println(expectedRange[0]);
    // System.out.println(expectedRange[1]);

    return bestNum;
  }

  /**
   * The process we do while in Ready state.
   * 
   * <p>Wait for the physical units to tell us they are ready and change to normal
   * mode.

   * @param incoming the incoming mailbox
   * @param outgoing the outgoing mailbox
   */
  public void doReady(Mailbox incoming, Mailbox outgoing) {
    if (extractAllMatches(MessageKind.PHYSICAL_UNITS_READY, incoming).length == 1) {
      this.mode = State.NORMAL;
      outgoing.send(new Message(MessageKind.MODE_m, Mailbox.Mode.NORMAL));
    }
  }

  /**
   * The process we carry out while waiting for the steam boiler to tell us it's
   * waiting. Once it's waiting, initiate initial tasks.
   * 
   * <p>Wait for the steam boiler to tell us it's waiting. Then check if there's any
   * errors with the other messages. If the water level isn't right then either
   * fill or open valve until level is acceptable. Once that's accpetable tell the
   * units we are ready and move to ready state.

   * @param incoming the incoming mailbox
   * @param outgoing the outgoing mailbox
   */
  public void doWaiting(Mailbox incoming, Mailbox outgoing) {

    if (extractAllMatches(MessageKind.STEAM_BOILER_WAITING, incoming).length != 1) {
      return;
    }

    Message levelMessage = extractOnlyMatch(MessageKind.LEVEL_v, incoming);
    Message steamMessage = extractOnlyMatch(MessageKind.STEAM_v, incoming);
    if (levelMessage == null || steamMessage == null) {
      emergencyMode(outgoing);
    } else if (steamMessage.getDoubleParameter() != 0) {
      emergencyMode(outgoing);
    } else if (levelMessage.getDoubleParameter() < 0
        || levelMessage.getDoubleParameter() >= this.configuration.getCapacity()) {
      System.out.println("sensor broken");
      emergencyMode(outgoing);
    } else if (levelMessage.getDoubleParameter() > this.configuration.getMaximalNormalLevel()
        || levelMessage.getDoubleParameter() < this.configuration.getMinimalNormalLevel()) {
      hitInitialTarget(levelMessage, steamMessage, outgoing);
    } else {
      closeAllPumps(outgoing);
      if (this.valveOpen) {
        outgoing.send(new Message(MessageKind.VALVE));
        System.out.println("Closing valve at end");
        this.valveOpen = false;
      }
      outgoing.send(new Message(MessageKind.PROGRAM_READY));
      this.mode = State.READY;

    }

  }

  /**
   * Carry out standard tasks for emergency mode. By changing state and alerting
   * units.

   * @param outgoing the outgoing mailbox
   */
  private void emergencyMode(Mailbox outgoing) {
    this.mode = State.EMERGENCY_STOP;
    outgoing.send(new Message(MessageKind.MODE_m, Mailbox.Mode.EMERGENCY_STOP));
  }

  /**
   * Fill the boiler initially.
   * 
   * <p>Find out what number of pumps gets us closest to the target. Then turn on the
   * pumps and close the rest.

   * @param levelMessage the level message
   * @param outgoing     the outgoing mailbox
   */
  private void initialFill(Message levelMessage, Mailbox outgoing) {
    int best = 1;
    double dist = 9999;

    for (int i = 1; i <= this.configuration.getNumberOfPumps(); i++) {
      double level = levelMessage.getDoubleParameter()
          + (5 * this.configuration.getPumpCapacity(0) * i);
      if (Math.abs(level - this.target) < dist) {
        best = i;
        dist = Math.abs(level - this.target);
        this.expectedRange[0] = level;

      }
    }

    for (int i = 0; i < this.configuration.getNumberOfPumps(); i++) {
      if (i < best) {
        outgoing.send(new Message(MessageKind.OPEN_PUMP_n, i));
        this.pumpsState[i] = true;
      } else {
        outgoing.send(new Message(MessageKind.CLOSE_PUMP_n, i));
        this.pumpsState[i] = false;
      }
    }

  }

  /**
   * Close all the pumps.

   * @param outgoing the outgoing mailbox
   */
  private void closeAllPumps(Mailbox outgoing) {
    for (int i = 0; i < this.configuration.getNumberOfPumps(); i++) {
      outgoing.send(new Message(MessageKind.CLOSE_PUMP_n, i));
      this.pumpsState[i] = false;
    }
  }

  /**
   * Hit the initial target during start up.
   * 
   * <p>If the water is too high, then open valve. Otherwise fill with pumps.

   * @param level    the level message
   * @param steam    the steam level message
   * @param outgoing the outgoing mailbox
   */
  private void hitInitialTarget(Message level, Message steam, Mailbox outgoing) {
    if (level.getDoubleParameter() > this.configuration.getMaximalNormalLevel()
        && !this.valveOpen) {
      outgoing.send(new Message(MessageKind.VALVE));
      this.valveOpen = true;
    } else if (level.getDoubleParameter() < this.configuration.getMinimalNormalLevel()) {
      // turnOnPumps(calcPumps(level, steam), outgoing);
      if (this.expectedRange[0] != -1 && this.expectedRange[0] != level.getDoubleParameter()) { 
        // something
        // is not
        // working
        // trigger degrade mode once done //FIXME
      }
      initialFill(level, outgoing);
      if (this.valveOpen) {
        outgoing.send(new Message(MessageKind.VALVE));
        System.out.println("Drained too much so close");
        this.valveOpen = false;
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
   * @return a boolean if there was a transmission failure
   */
  private boolean transmissionFailure(Message levelMessage, Message steamMessage,
      Message[] pumpStates, Message[] pumpControlStates) {
    // Check level readings
    if (pumpStates.length != configuration.getNumberOfPumps()) {
      // Nonsense pump state readings
      return true;
    } else if (pumpControlStates.length != configuration.getNumberOfPumps()) {
      // Nonsense pump control state readings
      return true;
    }

    for (int i = 0; i < pumpStates.length; i++) {
      if (pumpStates[i] == null) {
        return true;
      }
    }
    for (int i = 0; i < pumpControlStates.length; i++) {
      if (pumpControlStates[i] == null) {
        return true;
      }
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
   * @param incoming the incoming mailbox
   * @return an array of all ack messages
   */
//  private static Message[] extractAllAcks(Mailbox incoming) {
//    MessageKind[] repairs = { MessageKind.LEVEL_FAILURE_ACKNOWLEDGEMENT,
//        MessageKind.PUMP_FAILURE_ACKNOWLEDGEMENT_n, MessageKind.PUMP_FAILURE_ACKNOWLEDGEMENT_n,
//        MessageKind.STEAM_OUTCOME_FAILURE_ACKNOWLEDGEMENT };
//    int count = 0;
//    // Count the number of matches
//    for (int i = 0; i != incoming.size(); ++i) {
//      Message ith = incoming.read(i);
//      if (Arrays.stream(repairs).anyMatch(ith.getKind()::equals)) {
//        count = count + 1;
//      }
//    }
//    // Now, construct resulting array
//    Message[] matches = new Message[count];
//    int index = 0;
//    for (int i = 0; i != incoming.size(); ++i) {
//      Message ith = incoming.read(i);
//      if (Arrays.stream(repairs).anyMatch(ith.getKind()::equals)) {
//        matches[index++] = ith;
//      }
//    }
//    return matches;
//  }

  /**
   * Find and extract all messages that are repairs.
   *
   * @param kind     The kind of message to look for.
   * @param incoming The mailbox to search through.
   * @return The array of matches, which can empty if there were none.
   */
  private static Message[] extractAllRepairs(Mailbox incoming) {
    MessageKind[] repairs = { MessageKind.LEVEL_REPAIRED, MessageKind.PUMP_CONTROL_REPAIRED_n,
        MessageKind.PUMP_REPAIRED_n, MessageKind.STEAM_REPAIRED };
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

  // public boolean val(Mailbox incoming, MessageKind mk, int i) {
  //
  // for(int j = 0; j < incoming.size(); j++) {
  // if(incoming.read(j).getKind() == mk) {
  // if(incoming.read(j).getIntegerParameter() == i) {
  // return incoming.read(j).getBooleanParameter();
  // }
  // }
  // }
  // assert false;
  // return false;
  // }
}
