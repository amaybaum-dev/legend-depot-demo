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

package org.finos.legend.depot.store.api.projects;

import org.finos.legend.depot.domain.api.MetadataEventResponse;
import org.finos.legend.depot.domain.project.StoreProjectVersionData;

public interface UpdateProjectsVersions extends ProjectsVersions
{
    StoreProjectVersionData createOrUpdate(StoreProjectVersionData projectVersionData);

    MetadataEventResponse delete(String groupId, String artifactId);

    MetadataEventResponse deleteByVersionId(String groupId, String artifactId, String versionId);
}