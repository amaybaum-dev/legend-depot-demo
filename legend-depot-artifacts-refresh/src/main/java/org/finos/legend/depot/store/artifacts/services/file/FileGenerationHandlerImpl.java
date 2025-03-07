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

package org.finos.legend.depot.store.artifacts.services.file;

import org.finos.legend.depot.artifacts.repository.api.ArtifactRepository;
import org.finos.legend.depot.artifacts.repository.domain.ArtifactType;
import org.finos.legend.depot.domain.api.MetadataEventResponse;
import org.finos.legend.depot.domain.generation.file.FileGeneration;
import org.finos.legend.depot.domain.generation.file.StoredFileGeneration;
import org.finos.legend.depot.domain.project.StoreProjectData;
import org.finos.legend.depot.domain.version.VersionValidator;
import org.finos.legend.depot.services.api.generation.file.ManageFileGenerationsService;
import org.finos.legend.depot.store.artifacts.ArtifactLoadingException;
import org.finos.legend.depot.store.artifacts.api.generation.file.FileGenerationsArtifactsHandler;
import org.finos.legend.depot.store.artifacts.api.generation.file.FileGenerationsArtifactsProvider;
import org.finos.legend.sdlc.domain.model.entity.Entity;
import org.finos.legend.sdlc.serialization.EntityLoader;
import org.slf4j.Logger;

import javax.inject.Inject;
import java.io.File;
import java.util.Collections;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.finos.legend.depot.domain.generation.file.FileGeneration.GENERATION_CONFIGURATION;

public class FileGenerationHandlerImpl implements FileGenerationsArtifactsHandler
{

    public static final String TYPE = "type";
    public static final String PATH = "/";
    public static final String GENERATION_OUTPUT_PATH = "generationOutputPath";
    public static final String PURE_PACKAGE_SEPARATOR = "::";
    public static final String UNDERSCORE = "_";
    public static final String BLANK = "";
    private static final Logger LOGGER = org.slf4j.LoggerFactory.getLogger(FileGenerationHandlerImpl.class);
    protected final ManageFileGenerationsService generations;
    private final FileGenerationsArtifactsProvider provider;
    private final ArtifactRepository repository;


    @Inject
    public FileGenerationHandlerImpl(ArtifactRepository repository, FileGenerationsArtifactsProvider provider, ManageFileGenerationsService generations)
    {
        this.repository = repository;
        this.provider = provider;
        this.generations = generations;
    }



    public MetadataEventResponse refreshProjectVersionArtifacts(StoreProjectData projectData, String versionId, List<File> files)
    {
        MetadataEventResponse response = new MetadataEventResponse();
        try
        {
            List<Entity> projectEntities = getAllNonVersionedEntities(projectData.getGroupId(), projectData.getArtifactId(), versionId);
            List<Entity> fileGenerationEntities = filterEntitiesByFileGenerationEntities(projectEntities);
            List<FileGeneration> generatedFiles = provider.extractArtifacts(files);
            //handle files generated when a new master snapshot comes into picture
            if (versionId.equals(VersionValidator.MASTER_SNAPSHOT))
            {
                String message = String.format("removing prior %s artifacts for [%s-%s-%s]",provider.getType(),projectData.getGroupId(),projectData.getArtifactId(),versionId);
                response.addMessage(message);
                generations.delete(projectData.getGroupId(), projectData.getArtifactId(), versionId);
                LOGGER.info(message);
            }
            // handle files generated by FileGeneration Element
            HashSet<FileGeneration> processedGeneratedFiles = new HashSet<>();
            fileGenerationEntities.forEach(entity ->
            {
                String generationPath = (String) entity.getContent().get(GENERATION_OUTPUT_PATH);
                String elementPath = PATH + (generationPath != null ? generationPath : entity.getPath().replace(PURE_PACKAGE_SEPARATOR, UNDERSCORE));
                String codeSchemaGenerationType = (String) entity.getContent().get(TYPE);

                generatedFiles.stream().filter(gen -> gen.getPath().startsWith(elementPath)).forEach(gen ->
                {
                    FileGeneration generation = new FileGeneration(gen.getPath().replace(elementPath, BLANK), gen.getContent());
                    generations.createOrUpdate(new StoredFileGeneration(projectData.getGroupId(), projectData.getArtifactId(), versionId, entity.getPath(), codeSchemaGenerationType, generation));
                    processedGeneratedFiles.add(gen);
                });
            });

            // handle remaining files
            Map<String, Entity> entityMap = buildEntitiesByElementPathMap(projectEntities);
            Set<String> entityPaths = entityMap.keySet();
            generatedFiles.forEach(generatedFile ->
            {
                if (!processedGeneratedFiles.contains(generatedFile))
                {
                    Optional<String> entityPath = entityPaths.stream().filter(s -> generatedFile.getPath().startsWith(PATH + s)).findFirst();
                    if (!entityPath.isPresent())
                    {
                        String unableToHandle = String.format("Can't find element path for generated file with path %s",generatedFile.getPath());
                        LOGGER.warn(unableToHandle);
                    }
                    else
                    {
                        String elementPath = entityMap.get(entityPath.get()).getPath();
                        FileGeneration generation = new FileGeneration(generatedFile.getPath(), generatedFile.getContent());
                        generations.createOrUpdate(new StoredFileGeneration(projectData.getGroupId(), projectData.getArtifactId(), versionId, elementPath, null, generation));
                    }
                }
            });
            String message = String.format("processed [%s] generations for [%s-%s-%s] ", processedGeneratedFiles.size(), projectData.getGroupId(), projectData.getArtifactId(), versionId);
            LOGGER.info(message);
            response.addMessage(message);
        }
        catch (Exception e)
        {
           String message = String.format("Error processing generations update for %s-%s-%s , ERROR: [%s]", projectData.getGroupId(),projectData.getArtifactId(),versionId,e.getMessage());
           LOGGER.error(message);
           response.addError(message);
        }
        return response;
    }

    private Map<String, Entity> buildEntitiesByElementPathMap(List<Entity> entities)
    {
        Map<String, Entity> entityMap = new HashMap<>();
        entities.forEach(entity -> entityMap.put(entity.getPath().replace(PURE_PACKAGE_SEPARATOR, PATH), entity));
        return entityMap;
    }


    private List<Entity> getAllNonVersionedEntities(String groupId, String artifactId, String versionId)
    {
        List<File> files = repository.findFiles(ArtifactType.ENTITIES, groupId, artifactId, versionId);
        return files.stream().findFirst().map(file -> EntityLoader.newEntityLoader(file).getAllEntities().collect(Collectors.toList())).orElse(Collections.emptyList());
    }

    private List<Entity> filterEntitiesByFileGenerationEntities(List<Entity> entities)
    {
        return entities.stream().filter(en -> en.getClassifierPath().equalsIgnoreCase(GENERATION_CONFIGURATION)).collect(Collectors.toList());
    }

    @Override
    public void delete(String groupId,String artifactId,String versionId)
    {
        generations.delete(groupId,artifactId, versionId);
    }
}
