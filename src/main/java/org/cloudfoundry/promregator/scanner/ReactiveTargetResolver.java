package org.cloudfoundry.promregator.scanner;

import java.util.LinkedList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.apache.log4j.Logger;
import org.cloudfoundry.client.v2.applications.ApplicationResource;
import org.cloudfoundry.client.v2.applications.ListApplicationsResponse;
import org.cloudfoundry.client.v2.spaces.ListSpacesResponse;
import org.cloudfoundry.client.v2.spaces.SpaceResource;
import org.cloudfoundry.promregator.cfaccessor.CFAccessor;
import org.cloudfoundry.promregator.config.Target;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import reactor.core.Exceptions;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

@Component
public class ReactiveTargetResolver implements TargetResolver {
	private static final Logger log = Logger.getLogger(ReactiveTargetResolver.class);
	
	@Autowired
	private CFAccessor cfAccessor;
	
	@Override
	public List<ResolvedTarget> resolveTargets(List<Target> configTargets) {
		
		Flux<ResolvedTarget> resultFlux = Flux.fromIterable(configTargets)
			/* It would be possible to add .parallel() here
			 * However, the current memory footprint (i.e. memory throughput)
			 * is already a challenge for the garbage collector (see also
			 * issue #54). That is why we currently refrain from also running
			 * the requests here in parallel. 
			 * If measurements in the future show that this would not be a problem,
			 * we are still capable of introducing this later.
			 */
			.flatMapSequential(configTarget -> this.resolveSingleTarget(configTarget));
		
		return resultFlux.distinct().collectList().block();
	}
	
	public Flux<ResolvedTarget> resolveSingleTarget(Target configTarget) {
		if (configTarget.getApplicationName() != null
				&& configTarget.getSpaceName() != null) {
			// config target is already resolved
			ResolvedTarget rt = new ResolvedTarget(configTarget);
			return Flux.just(rt);
		}
		
		// on error handling: covered by "onErrorResume" in this method a little below
		Mono<String> orgIdMono = this.cfAccessor.retrieveOrgId(configTarget.getOrgName())
				.map( r -> r.getResources())
				.map( l -> l.get(0))
				.map( e -> e.getMetadata()) 
				.map( entry -> entry.getId());
		
		// on error handling: covered by "onErrorResume" in this method a little below
		Mono<String> spaceIdMono = orgIdMono.flatMap(orgId -> {
			return this.cfAccessor.retrieveSpaceId(orgId, configTarget.getSpaceName());
		}).map( r -> r.getResources())
			.map( l -> l.get(0))
			.map( e -> e.getMetadata())
			.map( entry -> entry.getId());
		
		Flux<String> applicationNamesFlux = selectApplications(configTarget, orgIdMono, spaceIdMono)
			.doOnError( e -> {
				log.warn(String.format("Exception was raised on resolving targets for org name '%s' and space name '%s'", configTarget.getOrgName(), configTarget.getSpaceName()), e);
			}).onErrorResume(__ -> Flux.empty());

		Flux<ResolvedTarget> result = applicationNamesFlux
			.map(appName -> {
				ResolvedTarget newTarget = new ResolvedTarget(configTarget);
				newTarget.setApplicationName(appName);
				
				return newTarget;
			});
		
		return result;
	}

	private static class InvalidResponseFromCFCloudConnector extends Exception {
		private static final long serialVersionUID = 3693506313317098423L;

		public InvalidResponseFromCFCloudConnector(String arg0) {
			super(arg0);
		}
	}
	
	private static class SpaceDetails {
		private String name;
		private String id;
		
		public SpaceDetails(String name, String id) {
			super();
			this.name = name;
			this.id = id;
		}

		/**
		 * @return the name
		 */
		public String getName() {
			return name;
		}

		/**
		 * @return the id
		 */
		public String getId() {
			return id;
		}
	}
	
	private Flux<SpaceDetails> resolveSpacesInConfigTarget(Target configTarget, Mono<String> orgIdMono) {
		/* NB: Now we have to consider three cases:
		 * Case 1: both spaceName and spaceRegex is empty => select all spaces
		 * Case 2: spaceName is null, but spaceRegex is filled => filter all spaces with the regex
		 * Case 3: spaceName is filled, but spaceRegex is null => select a single app
		 * In cases 1 and 2, we need the list of all spaces in the space.
		 */
		
		if (configTarget.getSpaceRegex() == null && configTarget.getSpaceName() != null) {
			// Case 3: we have the spaceName, but we also need its id
			Mono<String> spaceIdMono = orgIdMono.flatMap( orgId -> {
				return this.cfAccessor.retrieveSpaceId(orgId, configTarget.getSpaceName());
			}).map(l -> l.getResources())
			.map(l -> l.get(0))
			.map(e -> e.getMetadata())
			.map(md -> md.getId());
			
			Mono<SpaceDetails> spaceDetailsMono = spaceIdMono.map(spaceId -> {
				return new SpaceDetails(configTarget.getSpaceName(), spaceId);
			});
			
			return spaceDetailsMono.flux();
		}
		
		// Case 1 & 2: Get all spaces from org
		Mono<ListSpacesResponse> responseMono = orgIdMono.flatMap(orgId -> {
			return this.cfAccessor.retrieveSpaceIdsInOrg(orgId);
		});
		
		Flux<SpaceDetails> spacesInSelection = responseMono.map( r -> {
			List<SpaceResource> resources = r.getResources();
			if (resources == null) {
				log.error(String.format("Error on resolving spaces in org '%s'", configTarget.getOrgName()));
				throw Exceptions.propagate(new InvalidResponseFromCFCloudConnector("Unexpected empty response of the space resource list while reading all spaces in an org"));
			}
			
			return resources;
		}).flatMapMany( resources -> {
			List<SpaceDetails> spaceDetailsList = new LinkedList<>();
			for (SpaceResource sr : resources) {
				SpaceDetails sd = new SpaceDetails(sr.getEntity().getName(), sr.getMetadata().getId());
				spaceDetailsList.add(sd);
			}
			return Flux.fromIterable(spaceDetailsList);
		});
		
		Flux<SpaceDetails> filteredSpacesInOrg = spacesInSelection;
		if (configTarget.getSpaceRegex() != null) {
			// Case 2
			final Pattern filterPattern = Pattern.compile(configTarget.getSpaceRegex(), Pattern.CASE_INSENSITIVE);
			filteredSpacesInOrg = spacesInSelection.filter(spaceDetail -> {
				Matcher m = filterPattern.matcher(spaceDetail.getName());
				return m.matches();
			});
		}
		
		return filteredSpacesInOrg;
	}
	
	private Flux<String> selectApplications(Target configTarget, Mono<String> orgIdMono, Mono<String> spaceIdMono) {
		/* NB: Now we have to consider three cases:
		 * Case 1: both applicationName and applicationRegex is empty => select all apps
		 * Case 2: applicationName is null, but applicationRegex is filled => filter all apps with the regex
		 * Case 3: applicationName is filled, but applicationRegex is null => select a single app
		 * In cases 1 and 2, we need the list of all apps in the space.
		 */
		
		Flux<String> applicationsInSelection = null;
		
		if (configTarget.getApplicationRegex() == null && configTarget.getApplicationName() != null) {
			// Case 3: trivial
			return Flux.just(configTarget.getApplicationName());
		} else {
			// Case 1 & 2: Get all apps from space
			Mono<ListApplicationsResponse> responseMono = Mono.zip(orgIdMono, spaceIdMono)
				.flatMap( tuple -> { 
					return this.cfAccessor.retrieveAllApplicationIdsInSpace(tuple.getT1(), tuple.getT2()); 
				});
			
			applicationsInSelection = responseMono.map( r -> {
				List<ApplicationResource> resources = r.getResources();
				if (resources == null) {
					log.error(String.format("Error on resolving targets in org '%s' and space '%s'", configTarget.getOrgName(), configTarget.getSpaceName()));
					throw Exceptions.propagate(new InvalidResponseFromCFCloudConnector("Unexpected empty response of the application resource list while reading all applications in a space"));
				}
				
				return resources;
			}).flatMapMany(resources -> {
				List<String> appNames = new LinkedList<>();
				for (ApplicationResource ar : resources) {
					if (!isApplicationInScrapableState(ar.getEntity().getState())) {
						continue;
					}
					
					appNames.add(ar.getEntity().getName());
				}
				
				return Flux.fromIterable(appNames);
			});
		}
		
		Flux<String> filteredApplicationsInSpace = applicationsInSelection;
		if (configTarget.getApplicationRegex() != null) {
			// Case 2
			final Pattern filterPattern = Pattern.compile(configTarget.getApplicationRegex(), Pattern.CASE_INSENSITIVE);
			
			filteredApplicationsInSpace = applicationsInSelection.filter(appName -> {
				Matcher m = filterPattern.matcher(appName);
				return m.matches();
			});
		}

		return filteredApplicationsInSpace;
	}
	
	
	
	private boolean isApplicationInScrapableState(String state) {
		if ("STARTED".equals(state)) {
			return true;
		}
		
		/* TODO: To be enhanced, once we know of further states, which are
		 * also scrapable.
		 */
		
		return false;
	}
}
