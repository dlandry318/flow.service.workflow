package net.boomerangplatform.service.crud;

import java.util.List;
import org.springframework.data.domain.Pageable;
import net.boomerangplatform.model.FlowTeam;
import net.boomerangplatform.model.QuotasResponse;
import net.boomerangplatform.model.TeamQueryResult;
import net.boomerangplatform.model.TeamWorkflowSummary;
import net.boomerangplatform.mongo.entity.FlowTeamConfiguration;
import net.boomerangplatform.mongo.entity.FlowUserEntity;

public interface TeamService {

  FlowTeam createStandaloneTeam(String name);

  TeamQueryResult getAllAdminTeams(Pageable pageable);


  List<TeamWorkflowSummary> getAllTeams();

  void createFlowTeam(String higherLevelGroupId);


  List<TeamWorkflowSummary> getUserTeams(FlowUserEntity userEntity);

  List<FlowTeamConfiguration> getAllTeamProperties(String teamId);

  void deleteTeamProperty(String teamId, String configurationId);

  List<FlowTeamConfiguration> updateTeamProperty(String teamId, FlowTeamConfiguration property);

  FlowTeamConfiguration createNewTeamProperty(String teamId, FlowTeamConfiguration property);

  void updateTeamMembers(String teamId, List<String> teamMembers);

  FlowTeam getTeamById(String teamId);

  void updateTeam(String teamId, FlowTeam flow);

  QuotasResponse getTeamQuotas(String teamId);

  QuotasResponse resetTeamQuotas(String teamId);
}
