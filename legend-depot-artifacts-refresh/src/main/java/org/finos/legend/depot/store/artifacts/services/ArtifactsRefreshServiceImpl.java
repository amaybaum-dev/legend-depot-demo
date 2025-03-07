//  Copyright 2021 Goldman Sachs
//
//  Licensed under the Apache License, Version 2.0 (the "License");
//  you may not use this file except in compliance with the License.
//  You may obtain a copy of the License at
//
//       http://www.apache.org/licenses/LICENSE-2.0
//
//  Unless required by applicable law or agreed to in writing, software
//  distributed under the License is distributed on an "AS IS" BASIS,
//  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
//  See the License for the specific language governing permissions and
//  limitations under the License.
//

package org.finos.legend.depot.store.artifacts.services;

import org.eclipse.collections.impl.parallel.ParallelIterate;
import org.finos.legend.depot.artifacts.repository.api.ArtifactRepositoryException;
import org.finos.legend.depot.artifacts.repository.domain.VersionMismatch;
import org.finos.legend.depot.artifacts.repository.services.RepositoryServices;
import org.finos.legend.depot.domain.api.MetadataEventResponse;
import org.finos.legend.depot.domain.notifications.MetadataNotification;
import org.finos.legend.depot.domain.project.StoreProjectData;
import org.finos.legend.depot.services.api.projects.ProjectsService;
import org.finos.legend.depot.store.artifacts.api.ArtifactsRefreshService;
import org.finos.legend.depot.store.artifacts.api.ParentEventBuilder;
import org.finos.legend.depot.store.notifications.api.Queue;
import org.finos.legend.sdlc.domain.model.version.VersionId;
import org.slf4j.Logger;

import javax.inject.Inject;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;

import static org.finos.legend.depot.domain.version.VersionValidator.MASTER_SNAPSHOT;

public class ArtifactsRefreshServiceImpl implements ArtifactsRefreshService
{

    private static final String ALL = "all";
    private static final String MISSING = "missing";
    private static final Logger LOGGER = org.slf4j.LoggerFactory.getLogger(ArtifactsRefreshServiceImpl.class);
    private static final String REFRESH_ALL_VERSIONS_FOR_ALL_PROJECTS = "refreshAllVersionsForAllProjects";
    private static final String REFRESH_MASTER_SNAPSHOT_FOR_ALL_PROJECTS = "refreshMasterSnapshotForAllProjects";
    private static final String REFRESH_MASTER_SNAPSHOT_FOR_PROJECT = "refreshMasterSnapshotForProject";
    private static final String REFRESH_ALL_VERSIONS_FOR_PROJECT = "refreshAllVersionsForProject";
    private static final String REFRESH_PROJECTS_WITH_MISSING_VERSIONS = "refreshProjectsWithMissingVersions";
    private static final String REFRESH_PROJECT_VERSION_ARTIFACTS = "refreshProjectVersionArtifacts";



    private final ProjectsService projects;
    private final RepositoryServices repositoryServices;
    private final Queue workQueue;
    private final ProjectVersionRefreshHandler versionRefreshHandler;


    @Inject
    public ArtifactsRefreshServiceImpl(ProjectsService projects, RepositoryServices repositoryServices, Queue refreshWorkQueue, ProjectVersionRefreshHandler versionRefreshHandler)
    {
        this.projects = projects;
        this.repositoryServices = repositoryServices;
        this.workQueue = refreshWorkQueue;
        this.versionRefreshHandler = versionRefreshHandler;
    }

    @Override
    public MetadataEventResponse  refreshAllVersionsForAllProjects(boolean fullUpdate,boolean allVersions,boolean transitive,String parentEventId)
    {
        String parentEvent = ParentEventBuilder.build(ALL, ALL, ALL,parentEventId);
        MetadataNotification allVersionAllProjects = new MetadataNotification(ALL,ALL,ALL,ALL,fullUpdate,transitive,parentEvent);
        return versionRefreshHandler.executeWithTrace(REFRESH_ALL_VERSIONS_FOR_ALL_PROJECTS,allVersionAllProjects, () ->
                {
                    MetadataEventResponse result = new MetadataEventResponse();
                    String message = String.format("Executing: [%s-%s-%s], parentEventId :[%s], full/allVersions/transitive :[%s/%s/%s]",ALL,ALL,ALL,parentEvent,fullUpdate,allVersions,transitive);
                    result.addMessage(message);
                    LOGGER.info(message);
                    ParallelIterate.forEach(projects.getAllProjectCoordinates(),project -> result.combine(refreshAllVersionsForProject(project.getGroupId(),project.getArtifactId(),fullUpdate,allVersions,transitive,parentEvent)));
                    return result;
                }
        );
    }

    @Override
    public MetadataEventResponse refreshMasterSnapshotForAllProjects(boolean fullUpdate, boolean transitive, String parentEventId)
    {
        String parentEvent = ParentEventBuilder.build(ALL, ALL, MASTER_SNAPSHOT,parentEventId);
        MetadataNotification masterSnapshotAllProjects = new MetadataNotification(ALL,ALL,ALL,MASTER_SNAPSHOT,fullUpdate,transitive,parentEvent);
        return versionRefreshHandler.executeWithTrace(REFRESH_MASTER_SNAPSHOT_FOR_ALL_PROJECTS,masterSnapshotAllProjects, () ->
                {
                    MetadataEventResponse result = new MetadataEventResponse();
                    String message = String.format("Executing: [%s-%s-%s], parentEventId :[%s], full/transitive :[%s/%s]",ALL,ALL,MASTER_SNAPSHOT,parentEvent,fullUpdate,transitive);
                    result.addMessage(message);
                    LOGGER.info(message);
                    ParallelIterate.forEach(projects.getAllProjectCoordinates(),project -> result.addMessage(queueWorkToRefreshProjectVersion(project,MASTER_SNAPSHOT,fullUpdate,transitive,parentEvent)));
                    return result;
                }
        );
    }

    @Override
    public MetadataEventResponse refreshAllVersionsForProject(String groupId, String artifactId, boolean fullUpdate,boolean allVersions,boolean transitive,String parentEventId)
    {
        String parentEvent = ParentEventBuilder.build(groupId, artifactId, ALL, parentEventId);
        StoreProjectData projectData = getProject(groupId, artifactId);
        MetadataNotification allVersionForProject = new MetadataNotification(projectData.getProjectId(),groupId,artifactId,ALL,fullUpdate,transitive,parentEvent);
        return versionRefreshHandler.executeWithTrace(REFRESH_ALL_VERSIONS_FOR_PROJECT, allVersionForProject, () ->
        {
            MetadataEventResponse result = new MetadataEventResponse();
            String message = String.format("Executing: [%s-%s-%s], parentEventId :[%s], full/allVersions/transitive :[%s/%s/%s]", groupId, artifactId, ALL, parentEvent, fullUpdate, allVersions, transitive);
            result.addMessage(message);
            LOGGER.info(message);
            result.addMessage(queueWorkToRefreshProjectVersion(projectData, MASTER_SNAPSHOT, fullUpdate, transitive, parentEvent));
            result.combine(refreshAllVersionsForProject(projectData, allVersions, transitive, parentEvent));
            return result;
        });
    }

    @Override
    public MetadataEventResponse refreshMasterSnapshotForProject(String groupId, String artifactId, boolean fullUpdate, boolean transitive, String parentEventId)
    {
        String parentEvent = ParentEventBuilder.build(groupId, artifactId, MASTER_SNAPSHOT, parentEventId);
        StoreProjectData projectData = getProject(groupId, artifactId);
        MetadataNotification masterSnapshotForProject = new MetadataNotification(projectData.getProjectId(), groupId, artifactId, MASTER_SNAPSHOT, fullUpdate, transitive, parentEvent);
        return versionRefreshHandler.executeWithTrace(REFRESH_MASTER_SNAPSHOT_FOR_PROJECT, masterSnapshotForProject, () ->
        {
            MetadataEventResponse result = new MetadataEventResponse();
            String message = String.format("Executing: [%s-%s-%s], parentEventId :[%s], full/transitive :[%s/%s]", groupId, artifactId, MASTER_SNAPSHOT, parentEvent, fullUpdate, transitive);
            result.addMessage(message);
            LOGGER.info(message);
            result.addMessage(queueWorkToRefreshProjectVersion(projectData, MASTER_SNAPSHOT, fullUpdate, transitive, parentEvent));
            return result;
        });
    }

    @Override
    public MetadataEventResponse refreshVersionForProject(String groupId, String artifactId, String versionId, boolean transitive,String parentEventId)
    {
        String parentEvent = ParentEventBuilder.build(groupId, artifactId, versionId, parentEventId);
        StoreProjectData projectData = getProject(groupId, artifactId);
        MetadataNotification versionForProject = new MetadataNotification(projectData.getProjectId(),groupId,artifactId,versionId,true,transitive,parentEvent);
        return versionRefreshHandler.executeWithTrace(REFRESH_PROJECT_VERSION_ARTIFACTS, versionForProject, () ->
        {
            MetadataEventResponse result = new MetadataEventResponse();
            String message = String.format("Executing: [%s-%s-%s], parentEventId :[%s], full/transitive :[%s/%s]",groupId,artifactId,versionId,parentEvent,true,transitive);
            result.addMessage(message);
            LOGGER.info(message);
            result.addMessage(queueWorkToRefreshProjectVersion(projectData, versionId, true, transitive, parentEvent));
            return result;
        });
    }

    @Override
    public MetadataEventResponse refreshProjectsWithMissingVersions(String parentEventId)
    {
        String parentEvent = ParentEventBuilder.build(ALL, ALL,MISSING,parentEventId);
        MetadataNotification missingVersions = new MetadataNotification(ALL,ALL,ALL,MISSING,true,false,parentEvent);
        return versionRefreshHandler.executeWithTrace(REFRESH_PROJECTS_WITH_MISSING_VERSIONS,missingVersions, () ->
        {
            MetadataEventResponse response = new MetadataEventResponse();
            String infoMessage = String.format("Executing: [%s-%s-%s], parentEventId :[%s], full/transitive :[%s/%s]",ALL,ALL,MISSING,parentEventId,true,false);
            response.addMessage(infoMessage);
            LOGGER.info(infoMessage);
            List<VersionMismatch> projectsWithMissingVersions = this.repositoryServices.findVersionsMismatches().stream().filter(r -> !r.versionsNotInStore.isEmpty()).collect(Collectors.toList());
            String countInfo = String.format("Starting fixing [%s] projects with missing versions",projectsWithMissingVersions.size());
            LOGGER.info(countInfo);
            response.addMessage(countInfo);
            AtomicInteger totalMissingVersions = new AtomicInteger();
            projectsWithMissingVersions.forEach(vm ->
                    {
                        vm.versionsNotInStore.forEach(missingVersion ->
                                {
                                    try
                                    {
                                        String message = String.format("queued fixing missing version: %s-%s-%s ", vm.groupId, vm.artifactId, missingVersion);
                                        LOGGER.info(message);
                                        response.addMessage(queueWorkToRefreshProjectVersion(getProject(vm.groupId, vm.artifactId), missingVersion, true,false,parentEventId));

                                    }
                                    catch (Exception e)
                                    {
                                        String message = String.format("queuing failed for missing version: %s-%s-%s ", vm.groupId, vm.artifactId, missingVersion);
                                        LOGGER.error(message);
                                        response.addError(message);
                                    }
                                    totalMissingVersions.getAndIncrement();
                                }
                        );
                    }
            );
            LOGGER.info("Fixed [{}] missing versions",totalMissingVersions);
            return response;
        });
    }

    private MetadataEventResponse refreshAllVersionsForProject(StoreProjectData projectData, boolean allVersions, boolean transitive, String parentEvent)
    {
        String parentEventId = ParentEventBuilder.build(projectData.getGroupId(), projectData.getArtifactId(), ALL, parentEvent);
        MetadataEventResponse response = new MetadataEventResponse();

        String projectArtifacts = String.format("%s: [%s-%s]", projectData.getProjectId(), projectData.getGroupId(), projectData.getArtifactId());
        if (this.repositoryServices.areValidCoordinates(projectData.getGroupId(), projectData.getArtifactId()))
        {
            LOGGER.info("Fetching {} versions from repository", projectArtifacts);
            List<VersionId> repoVersions;
            try
            {
                repoVersions = this.repositoryServices.findVersions(projectData.getGroupId(), projectData.getArtifactId());
            }
            catch (ArtifactRepositoryException e)
            {
                response.addError(e.getMessage());
                return response;
            }

            if (repoVersions != null && !repoVersions.isEmpty())
            {
                List<VersionId> candidateVersions;
                Optional<VersionId> latestVersion = projects.getLatestVersion(projectData.getGroupId(), projectData.getArtifactId());
                if (!allVersions && latestVersion.isPresent())
                {
                    candidateVersions = calculateCandidateVersions(repoVersions, latestVersion.get());
                }
                else
                {
                    candidateVersions  = repoVersions;
                }
                String versionInfoMessage = String.format("%s found [%s] versions to update: %s", projectArtifacts, candidateVersions.size(), candidateVersions);
                LOGGER.info(versionInfoMessage);
                response.addMessage(versionInfoMessage);
                candidateVersions.forEach(v -> response.addMessage(queueWorkToRefreshProjectVersion(projectData, v.toVersionIdString(),true, transitive, parentEventId)));
                LOGGER.info("Finished processing all versions {}{}", projectData.getGroupId(), projectData.getArtifactId());
            }
        }
        else
        {
            String badCoordinatesMessage = String.format("invalid coordinates : [%s-%s] ", projectData.getGroupId(), projectData.getArtifactId());
            LOGGER.error(badCoordinatesMessage);
            response.logError(badCoordinatesMessage);
        }
        return response;
    }

    List<VersionId> calculateCandidateVersions(List<VersionId> repoVersions, VersionId latest)
    {
        return repoVersions.stream().filter(v -> v.compareTo(latest) > 0).collect(Collectors.toList());
    }

    private String queueWorkToRefreshProjectVersion(StoreProjectData projectData, String versionId, boolean fullUpdate, boolean transitive, String parentEvent)
    {
        return String.format("queued: [%s-%s-%s], parentEventId :[%s], full/transitive :[%s/%s],event id :[%s] ",
                projectData.getGroupId(),projectData.getArtifactId(),versionId,parentEvent,fullUpdate,transitive,this.workQueue.push(new MetadataNotification(projectData.getProjectId(),projectData.getGroupId(),projectData.getArtifactId(),versionId,fullUpdate,transitive,parentEvent)));
    }

    private StoreProjectData getProject(String groupId, String artifactId)
    {
        Optional<StoreProjectData> found = projects.findCoordinates(groupId, artifactId);
        if (!found.isPresent())
        {
            throw new IllegalArgumentException("can't find project for " + groupId + "-" + artifactId);
        }
        return found.get();
    }

}
