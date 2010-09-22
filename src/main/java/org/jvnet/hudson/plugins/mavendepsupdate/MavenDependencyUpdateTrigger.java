package org.jvnet.hudson.plugins.mavendepsupdate;

import static hudson.Util.fixNull;
import hudson.Extension;
import hudson.PluginFirstClassLoader;
import hudson.PluginWrapper;
import hudson.model.BuildableItem;
import hudson.model.Cause;
import hudson.model.FreeStyleProject;
import hudson.model.Hudson;
import hudson.model.Item;
import hudson.scheduler.CronTabList;
import hudson.tasks.Builder;
import hudson.tasks.Maven;
import hudson.triggers.TimerTrigger.TimerTriggerCause;
import hudson.triggers.Trigger;
import hudson.triggers.TriggerDescriptor;
import hudson.util.FormValidation;

import java.io.File;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.logging.Logger;

import org.apache.commons.lang.StringUtils;
import org.apache.maven.artifact.ArtifactUtils;
import org.apache.maven.artifact.InvalidRepositoryException;
import org.apache.maven.artifact.repository.ArtifactRepository;
import org.apache.maven.execution.DefaultMavenExecutionRequest;
import org.apache.maven.execution.MavenExecutionRequest;
import org.apache.maven.execution.MavenExecutionRequestPopulationException;
import org.apache.maven.execution.MavenExecutionRequestPopulator;
import org.apache.maven.project.DefaultDependencyResolutionRequest;
import org.apache.maven.project.DependencyResolutionException;
import org.apache.maven.project.DependencyResolutionResult;
import org.apache.maven.project.DuplicateProjectException;
import org.apache.maven.project.MavenProject;
import org.apache.maven.project.ProjectBuilder;
import org.apache.maven.project.ProjectBuildingException;
import org.apache.maven.project.ProjectBuildingRequest;
import org.apache.maven.project.ProjectBuildingResult;
import org.apache.maven.project.ProjectDependenciesResolver;
import org.apache.maven.project.ProjectSorter;
import org.apache.maven.repository.DelegatingLocalArtifactRepository;
import org.apache.maven.repository.RepositorySystem;
import org.apache.maven.repository.internal.MavenRepositorySystemSession;
import org.apache.maven.settings.building.DefaultSettingsBuildingRequest;
import org.apache.maven.settings.building.SettingsBuilder;
import org.apache.maven.settings.building.SettingsBuildingException;
import org.apache.maven.settings.building.SettingsBuildingRequest;
import org.apache.maven.settings.building.SettingsBuildingResult;
import org.codehaus.plexus.DefaultContainerConfiguration;
import org.codehaus.plexus.DefaultPlexusContainer;
import org.codehaus.plexus.PlexusContainer;
import org.codehaus.plexus.PlexusContainerException;
import org.codehaus.plexus.classworlds.ClassWorld;
import org.codehaus.plexus.classworlds.realm.ClassRealm;
import org.codehaus.plexus.component.repository.exception.ComponentLookupException;
import org.codehaus.plexus.util.dag.CycleDetectedException;
import org.kohsuke.stapler.DataBoundConstructor;
import org.kohsuke.stapler.QueryParameter;
import org.sonatype.aether.artifact.Artifact;
import org.sonatype.aether.repository.LocalRepository;
import org.sonatype.aether.repository.RepositoryPolicy;
import org.sonatype.aether.repository.WorkspaceReader;
import org.sonatype.aether.repository.WorkspaceRepository;
import org.sonatype.aether.transfer.TransferCancelledException;
import org.sonatype.aether.transfer.TransferEvent;
import org.sonatype.aether.transfer.TransferListener;
import org.sonatype.aether.util.repository.ChainedWorkspaceReader;

import antlr.ANTLRException;

/**
 */
public class MavenDependencyUpdateTrigger
    extends Trigger<BuildableItem>
{

    private static final Logger LOGGER = Logger.getLogger( MavenDependencyUpdateTrigger.class.getName() );

   
    public static final File userMavenConfigurationHome = new File( System.getProperty( "user.home" ), ".m2" );

    public static final File DEFAULT_USER_SETTINGS_FILE = new File( userMavenConfigurationHome, "settings.xml" );

    public static final File DEFAULT_GLOBAL_SETTINGS_FILE =
        new File( System.getProperty( "maven.home", System.getProperty( "user.dir", "" ) ), "conf/settings.xml" );
    
    @DataBoundConstructor
    public MavenDependencyUpdateTrigger( String cron_value )
        throws ANTLRException
    {
        super( cron_value );
    }

    @Override
    public void run()
    {

        PluginWrapper pluginWrapper = Hudson.getInstance().getPluginManager().getPlugin( "maven-dependency-update-trigger" );
        PluginFirstClassLoader pluginFirstClassLoader = (PluginFirstClassLoader) pluginWrapper.classLoader;
        
        ProjectBuildingRequest projectBuildingRequest = null;

        // job.scheduleBuild(0, new TimerTriggerCause());
        ClassLoader origClassLoader = Thread.currentThread().getContextClassLoader();
        try
        {
            PlexusContainer plexusContainer = getPlexusContainer( pluginFirstClassLoader );

            Thread.currentThread().setContextClassLoader( plexusContainer.getContainerRealm() );
            LOGGER.info( " in run " + this.job.getRootDir().getAbsolutePath() );
            
            // FIXME projectBuilder doesn't load snapshots ???
            ProjectBuilder projectBuilder = plexusContainer.lookup( ProjectBuilder.class );
            
            // FIXME load userProperties from the job
            Properties userProperties = new Properties();

            
            projectBuildingRequest = getProjectBuildingRequest( userProperties, plexusContainer );
                        
            List<ProjectBuildingResult> projectBuildingResults = projectBuilder
                .build( Arrays.asList( new File( this.job.getRootDir(), "workspace/trunk/pom.xml" ) ), true,
                        projectBuildingRequest);

           
            ProjectDependenciesResolver projectDependenciesResolver = plexusContainer.lookup( ProjectDependenciesResolver.class );
            
            List<MavenProject> mavenProjects = new ArrayList<MavenProject>(projectBuildingResults.size());
            
            for (ProjectBuildingResult projectBuildingResult : projectBuildingResults )
            {
                mavenProjects.add( projectBuildingResult.getProject() );
            }
            
            ProjectSorter projectSorter = new ProjectSorter( mavenProjects );
                       
            // use the projects reactor model as a workspaceReader
            // if reactors are not available remotely dependencies resolve will failed
            // due to artifact not found
            
            final Map<String, MavenProject> projectMap = getProjectMap( mavenProjects );
            WorkspaceReader reactorRepository = new ReactorReader( projectMap,  new File( this.job.getRootDir(), "workspace/trunk" ) );

            MavenRepositorySystemSession mavenRepositorySystemSession = (MavenRepositorySystemSession) projectBuildingRequest
                .getRepositorySession();

            mavenRepositorySystemSession.setUpdatePolicy( RepositoryPolicy.UPDATE_POLICY_ALWAYS );
            mavenRepositorySystemSession.setWorkspaceReader( ChainedWorkspaceReader
                .newInstance( reactorRepository, projectBuildingRequest.getRepositorySession().getWorkspaceReader() ) );

            projectBuildingRequest.setForceUpdate( true );
            for ( MavenProject mavenProject : projectSorter.getSortedProjects() )
            {
                LOGGER.info( "resolve dependencies for project " + mavenProject.getId() );
                DefaultDependencyResolutionRequest dependencyResolutionRequest = new DefaultDependencyResolutionRequest(
                                                                                                                         mavenProject,
                                                                                                                         mavenRepositorySystemSession );

                DependencyResolutionResult dependencyResolutionResult = projectDependenciesResolver
                    .resolve( dependencyResolutionRequest );

            }

        }
        catch ( PlexusContainerException e )
        {
            e.printStackTrace();
            LOGGER.warning( e.getMessage() );
        }
        catch ( ComponentLookupException e )
        {
            e.printStackTrace();
            LOGGER.warning( e.getMessage() );
        }
        catch ( MavenExecutionRequestPopulationException e )
        {
            e.printStackTrace();
            LOGGER.warning( e.getMessage() );
        }
        catch ( SettingsBuildingException e )
        {
            e.printStackTrace();
            LOGGER.warning( e.getMessage() );
        }
        catch ( ProjectBuildingException e )
        {
            System.out.println("cause");
            e.getCause().printStackTrace();
            System.out.println("exception");
            e.printStackTrace();
        }
        catch ( DependencyResolutionException e )
        {
            e.printStackTrace();
            LOGGER.warning( e.getMessage() );
        }
        catch ( InvalidRepositoryException e )
        {
            e.printStackTrace();
            LOGGER.warning( e.getMessage() );
        }
        catch ( CycleDetectedException e )
        {
            // TODO Auto-generated catch block
            e.printStackTrace();
        }
        catch ( DuplicateProjectException e )
        {
            // TODO Auto-generated catch block
            e.printStackTrace();
        } 
        catch ( Exception e )
        {
            // TODO Auto-generated catch block
            e.printStackTrace();
        }        
        finally
        {
            Thread.currentThread().setContextClassLoader( origClassLoader );
        }
        if (projectBuildingRequest != null)
        {
            SnapshotTransfertListener snapshotTransfertListener = (SnapshotTransfertListener) projectBuildingRequest
                .getRepositorySession().getTransferListener();
            LOGGER.info( "result here snapshotDownloaded : " + snapshotTransfertListener.snapshotDownloaded );    
            if (snapshotTransfertListener.snapshotDownloaded)
            {
                job.scheduleBuild(0, new MavenDependencyUpdateTriggerCause());
            }
        }
    }
    
    PlexusContainer getPlexusContainer(PluginFirstClassLoader pluginFirstClassLoader) throws PlexusContainerException {
        DefaultContainerConfiguration conf = new DefaultContainerConfiguration();
        ClassWorld world = new ClassWorld();
        ClassRealm classRealm = new ClassRealm( world, "project-building", pluginFirstClassLoader );
        // olamy yup hackish but it's needed for plexus-shim which a URLClassLoader and PluginFirstClassLoader is not
        for ( URL url : pluginFirstClassLoader.getURLs() )
        {
            classRealm.addURL( url );
            LOGGER.fine(  "add url " + url.toExternalForm() );
        }
        conf.setRealm( classRealm );

        return new DefaultPlexusContainer( conf );
    }
    

    
    ProjectBuildingRequest getProjectBuildingRequest( Properties userProperties, PlexusContainer plexusContainer )
        throws ComponentLookupException, SettingsBuildingException, MavenExecutionRequestPopulationException,
        InvalidRepositoryException
    {

        MavenExecutionRequest request = new DefaultMavenExecutionRequest();

        request.setLoggingLevel( MavenExecutionRequest.LOGGING_LEVEL_DEBUG );

        SettingsBuilder settingsBuilder = plexusContainer.lookup( SettingsBuilder.class );

        RepositorySystem repositorySystem = plexusContainer.lookup( RepositorySystem.class );
        
        org.sonatype.aether.RepositorySystem repoSystem = plexusContainer.lookup( org.sonatype.aether.RepositorySystem.class );

        SettingsBuildingRequest settingsRequest = new DefaultSettingsBuildingRequest();
        // FIXME find from job configuration
        settingsRequest.setGlobalSettingsFile( DEFAULT_GLOBAL_SETTINGS_FILE );
        settingsRequest.setUserSettingsFile( DEFAULT_USER_SETTINGS_FILE );
        settingsRequest.setSystemProperties( System.getProperties() );
        settingsRequest.setUserProperties( userProperties );

        SettingsBuildingResult settingsBuildingResult = settingsBuilder.build( settingsRequest );

        MavenExecutionRequestPopulator executionRequestPopulator = plexusContainer
            .lookup( MavenExecutionRequestPopulator.class );

        executionRequestPopulator.populateFromSettings( request, settingsBuildingResult.getEffectiveSettings() );

        String localRepoPath = getLocalRepo().getAbsolutePath();
        
        if ( StringUtils.isBlank( localRepoPath ) )
        {
            localRepoPath = settingsBuildingResult.getEffectiveSettings().getLocalRepository();
        }
        if ( StringUtils.isBlank( localRepoPath ) )
        {
            localRepoPath = RepositorySystem.defaultUserLocalRepository.getAbsolutePath();
        }

        MavenRepositorySystemSession session = new MavenRepositorySystemSession();
        
        //if (session.getLocalRepositoryManager() == null)
        //{
        //    session.setLocalRepositoryManager( new EnhancedLocalRepositoryManager( new File(localRepoPath) ) );
        //}
        SnapshotTransfertListener snapshotTransfertListener = new SnapshotTransfertListener();
        session.setTransferListener( snapshotTransfertListener );
        
        
        LocalRepository localRepo = new LocalRepository( localRepoPath );
        session.setLocalRepositoryManager( repoSystem.newLocalRepositoryManager( localRepo ) );
        
        ArtifactRepository localArtifactRepository = repositorySystem.createLocalRepository( new File( localRepoPath ) );
        
        request.setLocalRepository( new DelegatingLocalArtifactRepository( localArtifactRepository ) );

        
        
        request.getProjectBuildingRequest().setRepositorySession( session );
        
        return request.getProjectBuildingRequest();
    }
    private Map<String, MavenProject> getProjectMap( List<MavenProject> projects )
    {
        Map<String, MavenProject> index = new LinkedHashMap<String, MavenProject>();
        
        for ( MavenProject project : projects )
        {
            String projectId = ArtifactUtils.key( project.getGroupId(), project.getArtifactId(), project.getVersion() );
            index.put( projectId, project );
        }
        
        return index;
    }
    
    

    private File getLocalRepo()
    {
        boolean usePrivateRepo = usePrivateRepo();
        if ( usePrivateRepo )
        {
            return new File( this.job.getRootDir(), "workspace/.repository" );
        }
        return RepositorySystem.defaultUserLocalRepository;
    }
    
    @Extension
    public static class DescriptorImpl
        extends TriggerDescriptor
    {
        public boolean isApplicable( Item item )
        {
            return item instanceof BuildableItem;
        }

        public String getDisplayName()
        {
            return "m2 deps update";
        }

        @Override
        public String getHelpFile()
        {
            return "/help/project-config/timer.html";
        }

        /**
         * Performs syntax check.
         */
        public FormValidation doCheck( @QueryParameter String value )
        {
            try
            {
                String msg = CronTabList.create( fixNull( value ) ).checkSanity();
                if ( msg != null )
                    return FormValidation.warning( msg );
                return FormValidation.ok();
            }
            catch ( ANTLRException e )
            {
                return FormValidation.error( e.getMessage() );
            }
        }
    }

    public static class MavenDependencyUpdateTriggerCause
        extends Cause
    {
        @Override
        public String getShortDescription()
        {
            return "maven SNAPSHOT dependency update cause";
        }

        @Override
        public boolean equals( Object o )
        {
            return o instanceof MavenDependencyUpdateTriggerCause;
        }

        @Override
        public int hashCode()
        {
            return 5 * 2;
        }
    }
    
    private boolean usePrivateRepo()
    {
        // check if FreeStyleProject
        if (this.job instanceof FreeStyleProject )
        {
            FreeStyleProject fp = (FreeStyleProject) this.job;
            for(Builder b : fp.getBuilders())
            {
                if (b instanceof Maven)
                {
                    if (( (Maven) b ).usePrivateRepository)
                    {
                        return true;
                    }
                }
            }
            return false;
        }
        // check if there is a method called usesPrivateRepository
        try
        {
            Method method = this.job.getClass().getMethod( "usesPrivateRepository", null );
            Boolean bool = (Boolean) method.invoke( this.job, null );
            return bool.booleanValue();
        }
        catch ( SecurityException e )
        {
            LOGGER.fine(  e.getMessage() );
        }
        catch ( NoSuchMethodException e )
        {
            LOGGER.fine(  e.getMessage() );
        }
        catch ( IllegalArgumentException e )
        {
            LOGGER.fine(  e.getMessage() );
        }
        catch ( IllegalAccessException e )
        {
            LOGGER.fine(  e.getMessage() );
        }
        catch ( InvocationTargetException e )
        {
            // TODO Auto-generated catch block
            e.printStackTrace();
        }
        
        return false;
    }
    
    private static class SnapshotTransfertListener implements TransferListener
    {
        
        boolean snapshotDownloaded = false;

       
        public void transferCorrupted( TransferEvent arg0 )
            throws TransferCancelledException
        {
            // no op
        }

        public void transferFailed( TransferEvent arg0 )
        {
            // no op       
        }

        public void transferInitiated( TransferEvent arg0 )
            throws TransferCancelledException
        {
            // no op
        }

        public void transferProgressed( TransferEvent arg0 )
            throws TransferCancelledException
        {
            // no op
        }

        public void transferStarted( TransferEvent arg0 )
            throws TransferCancelledException
        {
            // no op
        }

        public void transferSucceeded( TransferEvent transferEvent )
        {
            if ( transferEvent != null && transferEvent.getResource() != null )
            {
                File file = transferEvent.getResource().getFile();
                if ( file != null && transferEvent.getResource().getResourceName().contains( "SNAPSHOT" ) )
                {
                    // filtering on maven metadata
                    if ( file.getName().endsWith( ".jar" ) || file.getName().endsWith( ".war" ) )
                    {
                        LOGGER.info( "download " + file.getName() );
                        snapshotDownloaded = true;
                    }
                }
            }
        }
        
    }

    // source copied from ASF repo org.apache.maven.ReactorReader
    // FIXME simplify more !!
    static class ReactorReader
        implements WorkspaceReader
    {

        private Map<String, MavenProject> projectsByGAV;

        private Map<String, List<MavenProject>> projectsByGA;

        private WorkspaceRepository repository;
        
        private File workspaceRoot;

        public ReactorReader( Map<String, MavenProject> reactorProjects, File workspaceRoot )
        {
            projectsByGAV = reactorProjects;
            this.workspaceRoot = workspaceRoot;
            projectsByGA = new HashMap<String, List<MavenProject>>( reactorProjects.size() * 2 );
            for ( MavenProject project : reactorProjects.values() )
            {
                String key = ArtifactUtils.versionlessKey( project.getGroupId(), project.getArtifactId() );

                List<MavenProject> projects = projectsByGA.get( key );

                if ( projects == null )
                {
                    projects = new ArrayList<MavenProject>( 1 );
                    projectsByGA.put( key, projects );
                }

                projects.add( project );
            }

            repository = new WorkspaceRepository( "reactor", new HashSet<String>( projectsByGAV.keySet() ) );
        }

        private File find( MavenProject project, Artifact artifact )
        {
            if ( "pom".equals( artifact.getExtension() ) )
            {
                return project.getFile();
            }

            return findMatchingArtifact( project, artifact ).getFile();
        }

        /**
         * Tries to resolve the specified artifact from the artifacts of the given project.
         * 
         * @param project The project to try to resolve the artifact from, must not be <code>null</code>.
         * @param requestedArtifact The artifact to resolve, must not be <code>null</code>.
         * @return The matching artifact from the project or <code>null</code> if not found.
         */
        private org.apache.maven.artifact.Artifact findMatchingArtifact( MavenProject project,
                                                                         Artifact requestedArtifact )
        {
            String requestedRepositoryConflictId = getConflictId( requestedArtifact );

            org.apache.maven.artifact.Artifact mainArtifact = project.getArtifact();
            if ( requestedRepositoryConflictId.equals( getConflictId( mainArtifact ) ) )
            {
                mainArtifact.setFile( new File( workspaceRoot, project.getArtifactId() ) );
                return mainArtifact;
            }

            Collection<org.apache.maven.artifact.Artifact> attachedArtifacts = project.getAttachedArtifacts();
            if ( attachedArtifacts != null && !attachedArtifacts.isEmpty() )
            {
                for ( org.apache.maven.artifact.Artifact attachedArtifact : attachedArtifacts )
                {
                    if ( requestedRepositoryConflictId.equals( getConflictId( attachedArtifact ) ) )
                    {
                        attachedArtifact.setFile( new File( workspaceRoot, project.getArtifactId() ) );
                        return attachedArtifact;
                    }
                }
            }

            return null;
        }

        /**
         * Gets the repository conflict id of the specified artifact. Unlike the dependency conflict id, the repository
         * conflict id uses the artifact file extension instead of the artifact type. Hence, the repository conflict id more
         * closely reflects the identity of artifacts as perceived by a repository.
         * 
         * @param artifact The artifact, must not be <code>null</code>.
         * @return The repository conflict id, never <code>null</code>.
         */
        private String getConflictId( org.apache.maven.artifact.Artifact artifact )
        {
            StringBuilder buffer = new StringBuilder( 128 );
            buffer.append( artifact.getGroupId() );
            buffer.append( ':' ).append( artifact.getArtifactId() );
            if ( artifact.getArtifactHandler() != null )
            {
                buffer.append( ':' ).append( artifact.getArtifactHandler().getExtension() );
            }
            else
            {
                buffer.append( ':' ).append( artifact.getType() );
            }
            if ( artifact.hasClassifier() )
            {
                buffer.append( ':' ).append( artifact.getClassifier() );
            }
            return buffer.toString();
        }

        private String getConflictId( Artifact artifact )
        {
            StringBuilder buffer = new StringBuilder( 128 );
            buffer.append( artifact.getGroupId() );
            buffer.append( ':' ).append( artifact.getArtifactId() );
            buffer.append( ':' ).append( artifact.getExtension() );
            if ( artifact.getClassifier().length() > 0 )
            {
                buffer.append( ':' ).append( artifact.getClassifier() );
            }
            return buffer.toString();
        }

        public File findArtifact( Artifact artifact )
        {
            String projectKey = artifact.getGroupId() + ':' + artifact.getArtifactId() + ':' + artifact.getVersion();

            MavenProject project = projectsByGAV.get( projectKey );

            if ( project != null )
            {
                return find( project, artifact );
            }

            return null;
        }

        public List<String> findVersions( Artifact artifact )
        {
            String key = artifact.getGroupId() + ':' + artifact.getArtifactId();

            List<MavenProject> projects = projectsByGA.get( key );
            if ( projects == null || projects.isEmpty() )
            {
                return Collections.emptyList();
            }

            List<String> versions = new ArrayList<String>();

            for ( MavenProject project : projects )
            {
                if ( find( project, artifact ) != null )
                {
                    versions.add( project.getVersion() );
                }
            }

            return Collections.unmodifiableList( versions );
        }

        public WorkspaceRepository getRepository()
        {
            return repository;
        }
    }
}
