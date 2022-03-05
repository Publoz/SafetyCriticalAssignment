package steam.boiler.tests;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;
import static steam.boiler.tests.TestUtils.LEVEL_FAILURE_DETECTION;

import static steam.boiler.tests.TestUtils.MODE_degraded;
import static steam.boiler.tests.TestUtils.MODE_emergencystop;
import static steam.boiler.tests.TestUtils.MODE_initialisation;
import static steam.boiler.tests.TestUtils.MODE_normal;
import static steam.boiler.tests.TestUtils.MODE_rescue;
import static steam.boiler.tests.TestUtils.PROGRAM_READY;
import static steam.boiler.tests.TestUtils.PUMP_CONTROL_FAILURE_DETECTION;
import static steam.boiler.tests.TestUtils.PUMP_FAILURE_DETECTION;
import static steam.boiler.tests.TestUtils.STEAM_FAILURE_DETECTION;
import static steam.boiler.tests.TestUtils.atleast;
import static steam.boiler.tests.TestUtils.clockForWithout;
import static steam.boiler.tests.TestUtils.clockOnceExpecting;
import static steam.boiler.tests.TestUtils.clockUntil;
import static steam.boiler.tests.TestUtils.exactly;

import org.junit.FixMethodOrder;
import org.junit.Test;
import org.junit.runners.MethodSorters;

import steam.boiler.core.MySteamBoilerController;
import steam.boiler.model.LevelSensorModels;
import steam.boiler.model.PhysicalUnits;
import steam.boiler.model.PumpControllerModels;
import steam.boiler.model.PumpModels;
import steam.boiler.model.SteamSensorModels;
import steam.boiler.util.Mailbox.Mode;
import steam.boiler.util.SteamBoilerCharacteristics;


public class MyTests {

//	/**
//	   * Check steam boiler starts pump to fill water up to minimum level before entering READY state.
//	   */
//	  @Test
//	  public void test_ValveBroken() {
//	    SteamBoilerCharacteristics config = SteamBoilerCharacteristics.DEFAULT;
//	    MySteamBoilerController controller = new MySteamBoilerController(config);
//	    PhysicalUnits model = new PhysicalUnits.Template(config).construct();
//	    // Wait at most 60s for controller to get to READY state
//	    model.setMode(PhysicalUnits.Mode.WAITING);
//	    clockUntil(60, controller, model, atleast(PROGRAM_READY));
//	    // At this point, level should be within normal bounds
//	    assertTrue(model.getBoiler().getWaterLevel() <= config.getMaximalNormalLevel());
//	    assertTrue(model.getBoiler().getWaterLevel() >= config.getMinimalNormalLevel());
//	    
//	    
//	  }
	  
	  @Test
	  public void test_steam_doesnt_change() {

		    // Explore various time frames for correct operation
		    for (int t = 120; t != 560; ++t) {
		      test_normal_operation(t, 4);
		      
		    }
		  }
	  
	  private void test_normal_operation(int time, int numberOfPumps) {
		    SteamBoilerCharacteristics config = SteamBoilerCharacteristics.DEFAULT;
		    config = config.setNumberOfPumps(numberOfPumps, config.getPumpCapacity(0));
		    MySteamBoilerController controller = new MySteamBoilerController(config);
		    PhysicalUnits model = new PhysicalUnits.Template(config).construct();
		    model.setMode(PhysicalUnits.Mode.WAITING);
		    // Clock system for a given amount of time. We're not expecting anything to go
		    // wrong during this time.
		    clockForWithout(time, controller, model, atleast(MODE_emergencystop));
		    // In an ideal setting, we expect the system to keep the level within the normal range at
		    // all times. Therefore, check water level is indeed within normal range.
		    
		    //Check steam level can't drop
		    assert model.getSteamSensor().getSteamOutputReading() == config.getMaximualSteamRate();
		    
		    if (model.getBoiler().getWaterLevel() > config.getMaximalLimitLevel()) {
		      fail("Water level above limit maximum (after " + time + "s with " + numberOfPumps
		          + " pumps)");
		    }
		    if (model.getBoiler().getWaterLevel() < config.getMinimalLimitLevel()) {
		      fail("Water level below limit minimum (after " + time + "s with " + numberOfPumps
		          + " pumps)");
		    }
		  }
	  
	
	  /**
	   * Check when a pump starts working at half, we notice
	   */
	  @Test
	  public void test_pumpsWorkinghalf() {
	    SteamBoilerCharacteristics config = SteamBoilerCharacteristics.DEFAULT;
	    MySteamBoilerController controller = new MySteamBoilerController(config);
	    PhysicalUnits model = new PhysicalUnits.Template(config).construct();
	    // Wait at most 60s for controller to get to READY state
	    model.setMode(PhysicalUnits.Mode.WAITING);
	    clockUntil(60, controller, model, atleast(PROGRAM_READY));
	    // At this point, level should be within normal bounds
	    assertTrue(model.getBoiler().getWaterLevel() <= config.getMaximalNormalLevel());
	    assertTrue(model.getBoiler().getWaterLevel() >= config.getMinimalNormalLevel());
	    
	    model.setPump(0, new PumpModels.ReducedHalf(0, config.getPumpCapacity(0)/2, model));
	   
	    clockUntil(60, controller, model, atleast(MODE_degraded));
	    
	  }
	  
	  
	  @Test
	  public void test_pumpClosed() {
		    SteamBoilerCharacteristics config = SteamBoilerCharacteristics.DEFAULT;
		    MySteamBoilerController controller = new MySteamBoilerController(config);
		    PhysicalUnits model = new PhysicalUnits.Template(config).construct();
		    // Wait at most 60s for controller to get to READY state
		    model.setMode(PhysicalUnits.Mode.WAITING);
		    clockUntil(60, controller, model, atleast(PROGRAM_READY));
		    // At this point, level should be within normal bounds
		    assertTrue(model.getBoiler().getWaterLevel() <= config.getMaximalNormalLevel());
		    assertTrue(model.getBoiler().getWaterLevel() >= config.getMinimalNormalLevel());
		    
		    model.setPump(0, new PumpModels.StuckClosed(0, 0.0, model));
		    
		    clockUntil(60, controller, model, atleast(MODE_degraded));
		    
		    model.setPump(0, new PumpModels.Ideal(0, config.getPumpCapacity(0), model));
		    model.setPumpStatus(0, PhysicalUnits.ComponentStatus.REPAIRED);
		    
		    clockUntil(60, controller, model, atleast(MODE_normal));
		    
		  }

	
}
