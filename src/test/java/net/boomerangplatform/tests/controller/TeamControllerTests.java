package net.boomerangplatform.tests.controller;

import static org.junit.Assert.assertEquals;
import java.util.List;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.security.test.context.support.WithUserDetails;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.ContextConfiguration;
import org.springframework.test.context.junit4.SpringJUnit4ClassRunner;
import net.boomerangplatform.Application;
import net.boomerangplatform.MongoConfig;
import net.boomerangplatform.controller.TeamController;
import net.boomerangplatform.model.CreateFlowTeam;
import net.boomerangplatform.mongo.entity.FlowTeamConfiguration;
import net.boomerangplatform.tests.FlowTests;

@RunWith(SpringJUnit4ClassRunner.class)
@ContextConfiguration(classes = {Application.class, MongoConfig.class})
@SpringBootTest
@ActiveProfiles("local")
@WithMockUser(roles = {"admin"})
@WithUserDetails("mdroy@us.ibm.com")
public class TeamControllerTests extends FlowTests {

  @Autowired
  private TeamController controller;

  @Test
  public void testGetTeams() {
    assertEquals(2, controller.getTeams().size());
  }

  @Test
  public void testCreateFlowTeam() {
    CreateFlowTeam request = new CreateFlowTeam();
    request.setName("TestFlowTeam");
    request.setCreatedGroupId("5cedb53261a23a0001e4c1b6");

    controller.createCiTeam(request);
    assertEquals(3, controller.getTeams().size());
  }

  @Test
  public void testGetAllTeamProperties() {
    List<FlowTeamConfiguration> configs =
        controller.getAllTeamProperties("5d1a1841f6ca2c00014c4309");
    assertEquals(0, configs.size());

    List<FlowTeamConfiguration> configs2 =
        controller.getAllTeamProperties("5d1a1841f6ca2c00014c4302");
    assertEquals(1, configs2.size());
  }

  @Test
  public void testDeleteTeamProperty() {
    controller.deleteTeamProperty("5d1a1841f6ca2c00014c4302",
        "df5f5749-4d30-41c3-803e-56b54b768407");
    assertEquals(0, controller.getAllTeamProperties("5d1a1841f6ca2c00014c4302").size());
  }

  @Test
  public void testUpdateTeamProperty() {
    assertEquals("Value",
        controller.getAllTeamProperties("5d1a1841f6ca2c00014c4302").get(0).getValue());

    FlowTeamConfiguration property = new FlowTeamConfiguration();
    property.setId("df5f5749-4d30-41c3-803e-56b54b768407");
    property.setValue("Updated Value");

    List<FlowTeamConfiguration> updatedConfigs = controller.updateTeamProperty(
        "5d1a1841f6ca2c00014c4302", property, "df5f5749-4d30-41c3-803e-56b54b768407");
    assertEquals("Updated Value", updatedConfigs.get(0).getValue());
  }

  @Test
  public void testCreateNewTeamProperty() {
    FlowTeamConfiguration property = new FlowTeamConfiguration();
    property.setKey("dylan.new.key");
    property.setValue("Dylan's New Value");

    FlowTeamConfiguration newConfig =
        controller.createNewTeamProperty("5d1a1841f6ca2c00014c4309", property);
    FlowTeamConfiguration newConfig2 =
        controller.createNewTeamProperty("5d1a1841f6ca2c00014c4302", property);

    assertEquals("dylan.new.key", newConfig.getKey());
    assertEquals("Dylan's New Value", newConfig.getValue());

    assertEquals("dylan.new.key", newConfig2.getKey());
    assertEquals("Dylan's New Value", newConfig2.getValue());
  }
}
