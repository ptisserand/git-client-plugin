package org.jenkinsci.plugins.gitclient;

import com.cloudbees.jenkins.plugins.sshcredentials.SSHUserPrivateKey;
import com.cloudbees.plugins.credentials.common.StandardCredentials;
import com.cloudbees.plugins.credentials.impl.UsernamePasswordCredentialsImpl;
import edu.umd.cs.findbugs.annotations.CheckForNull;
import edu.umd.cs.findbugs.annotations.NonNull;
import hudson.EnvVars;
import hudson.FilePath;
import hudson.Functions;
import hudson.Launcher;
import hudson.Launcher.LocalLauncher;
import hudson.Util;
import hudson.model.TaskListener;
import hudson.plugins.git.Branch;
import hudson.plugins.git.GitException;
import hudson.plugins.git.GitLockFailedException;
import hudson.plugins.git.IGitAPI;
import hudson.plugins.git.IndexEntry;
import hudson.plugins.git.Revision;
import hudson.remoting.Callable;
import hudson.slaves.SlaveComputer;
import hudson.util.ArgumentListBuilder;
import hudson.util.IOUtils;
import hudson.util.Secret;
import org.apache.commons.httpclient.Credentials;
import org.apache.commons.httpclient.HttpClient;
import org.apache.commons.httpclient.UsernamePasswordCredentials;
import org.apache.commons.httpclient.auth.AuthScope;
import org.apache.commons.httpclient.contrib.ssl.EasySSLProtocolSocketFactory;
import org.apache.commons.httpclient.methods.GetMethod;
import org.apache.commons.httpclient.protocol.Protocol;
import org.apache.commons.io.FileUtils;
import org.apache.commons.lang.StringUtils;
import org.eclipse.jgit.lib.Constants;
import org.eclipse.jgit.lib.ObjectId;
import org.eclipse.jgit.lib.Ref;
import org.eclipse.jgit.lib.Repository;
import org.eclipse.jgit.storage.file.FileRepositoryBuilder;
import org.eclipse.jgit.transport.RefSpec;
import org.eclipse.jgit.transport.RemoteConfig;
import org.eclipse.jgit.transport.URIish;
import org.kohsuke.stapler.framework.io.WriterOutputStream;

import java.io.*;
import java.net.URI;
import java.net.URISyntaxException;
import java.text.MessageFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.net.ssl.SSLException;

import static org.apache.commons.httpclient.params.HttpMethodParams.USER_AGENT;

/**
 * Implementation class using command line CLI ran as external command.
 * <b>
 * For internal use only, don't use directly. See {@link Git}
 * </b>
 */
public class CliGitAPIImpl extends LegacyCompatibleGitAPIImpl {

    static {
        if (Boolean.getBoolean(GitClient.class.getName() + ".untrustedSSL")) {
            Protocol.registerProtocol("https",
                new Protocol("https", new EasySSLProtocolSocketFactory(), 443));
        }
    }

    Launcher launcher;
    TaskListener listener;
    String gitExe;
    EnvVars environment;
    private Map<String, StandardCredentials> credentials = new HashMap<String, StandardCredentials>();
    private StandardCredentials defaultCredentials;

    protected CliGitAPIImpl(String gitExe, File workspace,
                         TaskListener listener, EnvVars environment) {
        super(workspace);
        this.listener = listener;
        this.gitExe = gitExe;
        this.environment = environment;

        launcher = new LocalLauncher(IGitAPI.verbose?listener:TaskListener.NULL);
    }

    public GitClient subGit(String subdir) {
        return new CliGitAPIImpl(gitExe, new File(workspace, subdir), listener, environment);
    }

    public void init() throws GitException, InterruptedException {
        if (hasGitRepo()) {
            throw new GitException(".git directory already exists! Has it already been initialised?");
        }
        Repository repo = getRepository();
        try {
            repo.create();
        } catch (IOException ioe) {
            throw new GitException("Error initiating git repo.", ioe);
        } finally {
            repo.close();
        }
    }

    public boolean hasGitRepo() throws GitException, InterruptedException {
        if (hasGitRepo(".git")) {
            // Check if this is actually a valid git repo by checking ls-files. If it's duff, this will
            // fail. HEAD is not guaranteed to be valid (e.g. new repo).
            try {
                launchCommand("rev-parse", "--is-inside-work-tree");
            } catch (Exception ex) {
                ex.printStackTrace(listener.error("Workspace has a .git repository, but it appears to be corrupt."));
                return false;
            }
            return true;
        }
        return false;
    }

    public boolean hasGitRepo( String GIT_DIR ) throws GitException {
        try {
            File dotGit = new File(workspace, GIT_DIR);
            return dotGit.exists();
        } catch (SecurityException ex) {
            throw new GitException("Security error when trying to check for .git. Are you sure you have correct permissions?",
                                   ex);
        } catch (Exception e) {
            throw new GitException("Couldn't check for .git", e);
        }
    }

    public List<IndexEntry> getSubmodules( String treeIsh ) throws GitException, InterruptedException {
        List<IndexEntry> submodules = lsTree(treeIsh,true);

        // Remove anything that isn't a submodule
        for (Iterator<IndexEntry> it = submodules.iterator(); it.hasNext();) {
            if (!it.next().getMode().equals("160000")) {
                it.remove();
            }
        }
        return submodules;
    }

    public FetchCommand fetch_() {
        return new FetchCommand() {
            public URIish url;
            public List<RefSpec> refspecs;
            public boolean prune;
            public boolean shallow;

            public FetchCommand from(URIish remote, List<RefSpec> refspecs) {
                this.url = remote;
                this.refspecs = refspecs;
                return this;
            }

            public FetchCommand prune() {
                this.prune = true;
                return this;
            }

            public FetchCommand shallow(boolean shallow) {
                this.shallow = shallow;
                return this;
            }

            public void execute() throws GitException, InterruptedException {
                listener.getLogger().println(
                        "Fetching upstream changes from " + url);

                ArgumentListBuilder args = new ArgumentListBuilder();
                args.add("fetch", "--tags", "--progress");

                StandardCredentials cred = credentials.get(url.toPrivateString());
                if (cred == null) cred = defaultCredentials;
                args.add(url);

                if (refspecs != null)
                    for (RefSpec rs: refspecs)
                        if (rs != null)
                            args.add(rs.toString());

                if (prune) args.add("--prune");

                if (shallow) args.add("--depth=1");

                launchCommandWithCredentials(args, workspace, cred, url);
            }
        };
    }

    public void fetch(URIish url, List<RefSpec> refspecs) throws GitException, InterruptedException {
        fetch_().from(url, refspecs).execute();
    }

    public void fetch(String remoteName, RefSpec... refspec) throws GitException, InterruptedException {
        listener.getLogger().println(
                                     "Fetching upstream changes"
                                     + (remoteName != null ? " from " + remoteName : ""));

        ArgumentListBuilder args = new ArgumentListBuilder();
        args.add("fetch", "-t");

        if (remoteName == null)
            remoteName = getDefaultRemote();

        String url = getRemoteUrl(remoteName);
        args.add(url);
        if (refspec != null && refspec.length > 0)
            for (RefSpec rs: refspec)
                if (rs != null)
                    args.add(rs.toString());


        StandardCredentials cred = credentials.get(url);
        if (cred == null) cred = defaultCredentials;
        launchCommandWithCredentials(args, workspace, cred, url);
    }

    public void fetch(String remoteName, RefSpec refspec) throws GitException, InterruptedException {
        fetch(remoteName, new RefSpec[] {refspec});
    }

    public void reset(boolean hard) throws GitException, InterruptedException {
    	try {
    		validateRevision("HEAD");
    	} catch (GitException e) {
    		listener.getLogger().println("No valid HEAD. Skipping the resetting");
    		return;
    	}
        listener.getLogger().println("Resetting working tree");

        ArgumentListBuilder args = new ArgumentListBuilder();
        args.add("reset");
        if (hard) {
            args.add("--hard");
        }

        launchCommand(args);
    }

    public CloneCommand clone_() {
        return new CloneCommand() {
            String url;
            String origin;
            String reference;
            boolean shallow,shared;

            public CloneCommand url(String url) {
                this.url = url;
                return this;
            }

            public CloneCommand repositoryName(String name) {
                this.origin = name;
                return this;
            }

            public CloneCommand shared() {
                this.shared = true;
                return this;
            }

            public CloneCommand shallow() {
                this.shallow = true;
                return this;
            }

            public CloneCommand reference(String reference) {
                this.reference = reference;
                return this;
            }

            public void execute() throws GitException, InterruptedException {

                URIish urIish = null;
                try {
                    urIish = new URIish(url);
                } catch (URISyntaxException e) {
                    listener.getLogger().println("Invalid repository " + url);
                    throw new IllegalArgumentException("Invalid repository " + url, e);
                }

                listener.getLogger().println("Cloning repository " + url);

                try {
                    Util.deleteContentsRecursive(workspace);
                } catch (Exception e) {
                    e.printStackTrace(listener.error("Failed to clean the workspace"));
                    throw new GitException("Failed to delete workspace", e);
                }

                // we don't run a 'git clone' command but git init + git fetch
                // this allows launchCommandWithCredentials() to pass credentials via a local gitconfig

                init();
                if (reference != null && !reference.equals("")) {
                    File referencePath = new File(reference);
                    if (!referencePath.exists())
                        listener.error("Reference path does not exist: " + reference);
                    else if (!referencePath.isDirectory())
                        listener.error("Reference path is not a directory: " + reference);
                    else {
                        // reference path can either be a normal or a base repository
                        File objectsPath = new File(referencePath, ".git/objects");
                        if (!objectsPath.isDirectory()) {
                            // reference path is bare repo
                            objectsPath = new File(referencePath, "objects");
                        }
                        if (!objectsPath.isDirectory())
                            listener.error("Reference path does not contain an objects directory (no git repo?): " + objectsPath);
                        else {
                            try {
                                File alternates = new File(workspace, ".git/objects/info/alternates");
                                PrintWriter w = new PrintWriter(alternates);
                                // git implementations on windows also use 
                                w.print(objectsPath.getAbsolutePath().replace('\\', '/'));
                                w.close();
                            } catch (FileNotFoundException e) {
                                listener.error("Failed to setup reference");
                            }
                        }
                    }
                }

                if (shared)
                    throw new UnsupportedOperationException("shared is unsupported, and considered dangerous");

                RefSpec refSpec = new RefSpec("+refs/heads/*:refs/remotes/"+origin+"/*");
                fetch_().from(urIish, Collections.singletonList(refSpec))
                        .shallow(shallow)
                        .execute();
            }

        };
    }

    public MergeCommand merge() {
        return new MergeCommand() {
            public ObjectId rev;
            public String strategy;

            public MergeCommand setRevisionToMerge(ObjectId rev) {
                this.rev = rev;
                return this;
            }

            public MergeCommand setStrategy(MergeCommand.Strategy strategy) {
                this.strategy = strategy.toString();
                return this;
            }

            public void execute() throws GitException, InterruptedException {
                try {
                    if (strategy != null && !strategy.isEmpty() && !strategy.equals(MergeCommand.Strategy.DEFAULT.toString())) {
                        launchCommand("merge", "-s", strategy, rev.name()); }
                    else {
                        launchCommand("merge", rev.name()); }
                } catch (GitException e) {
                    throw new GitException("Could not merge " + rev, e);
                }
            }
        };
    }

    public void clean() throws GitException, InterruptedException {
        reset(true);
        launchCommand("clean", "-fdx");
    }

    public ObjectId revParse(String revName) throws GitException, InterruptedException {

        String arg = sanitize(revName + "^{commit}");
        String result = launchCommand("rev-parse", arg);
        return ObjectId.fromString(firstLine(result).trim());
    }

    /**
     * On Windows command prompt, '^' is an escape character (http://en.wikipedia.org/wiki/Escape_character#Windows_Command_Prompt)
     * This isn't a problem if 'git' we are executing is git.exe, because '^' is a special character only for the command processor,
     * but if 'git' we are executing is git.cmd (which is the case of msysgit), then the arguments we pass in here ends up getting
     * processed by the command processor, and so 'xyz^{commit}' becomes 'xyz{commit}' and fails.
     * <p>
     * We work around this problem by surrounding this with double-quote on Windows.
     * Unlike POSIX, where the arguments of a process is modeled as String[], Win32 API models the
     * arguments of a process as a single string (see CreateProcess). When we surround one argument with a quote,
     * java.lang.ProcessImpl on Windows preserve as-is and generate a single string like the following to pass to CreateProcess:
     * <pre>
     *     git rev-parse "tag^{commit}"
     * </pre>
     * If we invoke git.exe, MSVCRT startup code in git.exe will handle escape and executes it as we expect.
     * If we invoke git.cmd, cmd.exe will not eats this ^ that's in double-quote. So it works on both cases.
     * <p>
     * Note that this is a borderline-buggy behaviour arguably. If I were implementing ProcessImpl for Windows
     * in JDK, My passing a string with double-quotes around it to be expanded to the following:
     * <pre>
     *    git rev-parse "\"tag^{commit}\""
     * </pre>
     * So this work around that we are doing for Windows relies on the assumption that Java runtime will not
     * change this behaviour.
     * <p>
     * Also note that on Unix we cannot do this. Similarly, other ways of quoting (like using '^^' instead of '^'
     * that you do on interactive command prompt) do not work either, because MSVCRT startup won't handle
     * those in the same way cmd.exe does.
     *
     * See JENKINS-13007 where this blew up on Windows users.
     * See https://github.com/msysgit/msysgit/issues/36 where I filed this as a bug to msysgit.
     **/
    private String sanitize(String arg) {
        if (Functions.isWindows())
            arg = '"'+arg+'"';
        return arg;
    }

    public ObjectId validateRevision(String revName) throws GitException, InterruptedException {
        String result = launchCommand("rev-parse", "--verify", revName);
        return ObjectId.fromString(firstLine(result).trim());
    }

    public String describe(String commitIsh) throws GitException, InterruptedException {
        String result = launchCommand("describe", "--tags", commitIsh);
        return firstLine(result).trim();
    }

    public void prune(RemoteConfig repository) throws GitException, InterruptedException {
        if (getRemoteUrl(repository.getName()) != null &&
            !getRemoteUrl(repository.getName()).equals("")) {
            ArgumentListBuilder args = new ArgumentListBuilder();
            args.add("remote", "prune", repository.getName());

            launchCommand(args);
        }
    }

    private @CheckForNull String firstLine(String result) {
        BufferedReader reader = new BufferedReader(new StringReader(result));
        String line;
        try {
            line = reader.readLine();
            if (line == null)
                return null;
            if (reader.readLine() != null)
                throw new GitException("Result has multiple lines");
        } catch (IOException e) {
            throw new GitException("Error parsing result", e);
        }

        return line;
    }

    public ChangelogCommand changelog() {
        return new ChangelogCommand() {
            final List<String> revs = new ArrayList<String>();
            Integer n = null;
            Writer out = null;

            public ChangelogCommand excludes(String rev) {
                revs.add(sanitize('^'+rev));
                return this;
            }

            public ChangelogCommand excludes(ObjectId rev) {
                return excludes(rev.name());
            }

            public ChangelogCommand includes(String rev) {
                revs.add(rev);
                return this;
            }

            public ChangelogCommand includes(ObjectId rev) {
                return includes(rev.name());
            }

            public ChangelogCommand to(Writer w) {
                this.out = w;
                return this;
            }

            public ChangelogCommand max(int n) {
                this.n = n;
                return this;
            }

            public void abort() {
                /* No cleanup needed to abort the CliGitAPIImpl ChangelogCommand */
            }

            public void execute() throws GitException, InterruptedException {
                ArgumentListBuilder args = new ArgumentListBuilder(gitExe, "whatchanged", "--no-abbrev", "-M", "--pretty=raw");
                if (n!=null)
                    args.add("-n").add(n);
                for (String rev : this.revs)
                    args.add(rev);

                if (out==null)  throw new IllegalStateException();

                try {
                    WriterOutputStream w = new WriterOutputStream(out);
                    try {
                        if (launcher.launch().cmds(args).envs(environment).stdout(w).stderr(listener.getLogger()).pwd(workspace).join() != 0)
                            throw new GitException("Error launching git whatchanged");
                    } finally {
                        w.flush();
                    }
                } catch (IOException e) {
                    throw new GitException("Error launching git whatchanged",e);
                }
            }
        };
    }

    public List<String> showRevision(ObjectId from, ObjectId to) throws GitException, InterruptedException {
        ArgumentListBuilder args = new ArgumentListBuilder("log", "--full-history", "--no-abbrev", "--format=raw", "-M", "-m", "--raw");
    	if (from != null){
            args.add(from.name() + ".." + to.name());
        } else {
            args.add("-1", to.name());
    	}

        StringWriter writer = new StringWriter();
        writer.write(launchCommand(args));
        return new ArrayList<String>(Arrays.asList(writer.toString().split("\\n")));
    }

    public void submoduleInit() throws GitException, InterruptedException {
        launchCommand("submodule", "init");
    }

    public void addSubmodule(String remoteURL, String subdir) throws GitException, InterruptedException {
        launchCommand("submodule", "add", remoteURL, subdir);
    }

    /**
     * Sync submodule URLs
     */
    public void submoduleSync() throws GitException, InterruptedException {
        // Check if git submodule has sync support.
        // Only available in git 1.6.1 and above
        launchCommand("submodule", "sync");
    }


    /**
     * Update submodules.
     *
     * @param recursive if true, will recursively update submodules (requires git>=1.6.5)
     *
     * @throws GitException if executing the Git command fails
     */
    public void submoduleUpdate(boolean recursive) throws GitException, InterruptedException {
        submoduleUpdate(recursive, null);
    }

    public void submoduleUpdate(boolean recursive, String reference) throws GitException, InterruptedException {
    	ArgumentListBuilder args = new ArgumentListBuilder();
    	args.add("submodule", "update");
    	if (recursive) {
            args.add("--init", "--recursive");
        }
        if (reference != null && !reference.equals("")) {
            File referencePath = new File(reference);
            if (!referencePath.exists())
                listener.error("Reference path does not exist: " + reference);
            else if (!referencePath.isDirectory())
                listener.error("Reference path is not a directory: " + reference);
            else
                args.add("--reference", reference);
        }

        launchCommand(args);
    }

    /**
     * Reset submodules
     *
     * @param recursive if true, will recursively reset submodules (requres git>=1.6.5)
     *
     * @throws GitException if executing the git command fails
     */
    public void submoduleReset(boolean recursive, boolean hard) throws GitException, InterruptedException {
        ArgumentListBuilder args = new ArgumentListBuilder();
        args.add("submodule", "foreach");
        if (recursive) {
            args.add("--recursive");
        }
        args.add("git reset");
        if (hard) {
            args.add("--hard");
        }

        launchCommand(args);
    }

    /**
     * Cleans submodules
     *
     * @param recursive if true, will recursively clean submodules (requres git>=1.6.5)
     *
     * @throws GitException if executing the git command fails
     */
    public void submoduleClean(boolean recursive) throws GitException, InterruptedException {
        submoduleReset(true, true);
        ArgumentListBuilder args = new ArgumentListBuilder();
        args.add("submodule", "foreach");
    	if (recursive) {
            args.add("--recursive");
    	}
    	args.add("git clean -fdx");

    	launchCommand(args);
    }

    /**
     * Get submodule URL
     *
     * @param name The name of the submodule
     *
     * @throws GitException if executing the git command fails
     */
    public @CheckForNull String getSubmoduleUrl(String name) throws GitException, InterruptedException {
        String result = launchCommand( "config", "--get", "submodule."+name+".url" );
        return StringUtils.trim(firstLine(result));
    }

    /**
     * Set submodule URL
     *
     * @param name The name of the submodule
     *
     * @param url The new value of the submodule's URL
     *
     * @throws GitException if executing the git command fails
     */
    public void setSubmoduleUrl(String name, String url) throws GitException, InterruptedException {
        launchCommand( "config", "submodule."+name+".url", url );
    }

    public @CheckForNull String getRemoteUrl(String name) throws GitException, InterruptedException {
        String result = launchCommand( "config", "--get", "remote."+name+".url" );
        return StringUtils.trim(firstLine(result));
    }

    public void setRemoteUrl(String name, String url) throws GitException, InterruptedException {
        launchCommand( "config", "remote."+name+".url", url );
    }

    public void addRemoteUrl(String name, String url) throws GitException, InterruptedException {
        launchCommand( "config", "--add", "remote."+name+".url", url );
    }

    public String getRemoteUrl(String name, String GIT_DIR) throws GitException, InterruptedException {
        String result
            = launchCommand("--git-dir=" + GIT_DIR,
                "config", "--get", "remote." + name + ".url");
        return firstLine(result).trim();
    }

    public void setRemoteUrl(String name, String url, String GIT_DIR ) throws GitException, InterruptedException {
        launchCommand( "--git-dir=" + GIT_DIR,
                       "config", "remote."+name+".url", url );
    }


    public String getDefaultRemote( String _default_ ) throws GitException, InterruptedException {
        BufferedReader rdr =
            new BufferedReader(
                new StringReader( launchCommand( "remote" ) )
            );

        List<String> remotes = new ArrayList<String>();

        String line;
        try {
            while ((line = rdr.readLine()) != null) {
                remotes.add(line);
            }
        } catch (IOException e) {
            throw new GitException("Error parsing remotes", e);
        }

        if (remotes.contains(_default_)) {
            return _default_;
        } else if ( remotes.size() >= 1 ) {
            return remotes.get(0);
        } else {
            throw new GitException("No remotes found!");
        }
    }

    /**
     * Get the default remote.
     *
     * @return "origin" if it exists, otherwise return the first remote.
     *
     * @throws GitException if executing the git command fails
     */
    public String getDefaultRemote() throws GitException, InterruptedException {
        return getDefaultRemote("origin");
    }

    public boolean isBareRepository(String GIT_DIR) throws GitException, InterruptedException {
        String ret;
        if ( "".equals(GIT_DIR) )
            ret = launchCommand(        "rev-parse", "--is-bare-repository");
        else {
            String gitDir = "--git-dir=" + GIT_DIR;
            ret = launchCommand(gitDir, "rev-parse", "--is-bare-repository");
        }

        return !"false".equals(firstLine(ret).trim());
    }

    private String pathJoin( String a, String b ) {
        return new File(a, b).toString();
    }

    /**
     * Fixes urls for submodule as stored in .git/config and
     * $SUBMODULE/.git/config for when the remote repo is NOT a bare repository.
     * It is only really possible to detect whether a repository is bare if we
     * have local access to the repository.  If the repository is remote, we
     * therefore must default to believing that it is either bare or NON-bare.
     * The defaults are according to the ending of the super-project
     * remote.origin.url:
     *  - Ends with "/.git":  default is NON-bare
     *  -         otherwise:  default is bare
     *  .
     *
     * @param listener The task listener.
     *
     * @throws GitException if executing the git command fails
     */
    public void fixSubmoduleUrls( String remote,
                                  TaskListener listener ) throws GitException, InterruptedException {
        boolean is_bare = true;

        URI origin;
        try {
            String url = getRemoteUrl(remote);

            // ensure that any /.git ending is removed
            String gitEnd = pathJoin("", ".git");
            if ( url.endsWith( gitEnd ) ) {
                url = url.substring(0, url.length() - gitEnd.length() );
                // change the default detection value to NON-bare
                is_bare = false;
            }

            origin = new URI( url );
        } catch (URISyntaxException e) {
            // Sometimes the URI is of a form that we can't parse; like
            //   user@git.somehost.com:repository
            // In these cases, origin is null and it's best to just exit early.
            return;
        } catch (Exception e) {
            throw new GitException("Could determine remote.origin.url", e);
        }

        if ( origin.getScheme() == null ||
             ( "file".equalsIgnoreCase( origin.getScheme() ) &&
               ( origin.getHost() == null || "".equals( origin.getHost() ) )
             )
           ) {
            // The uri is a local path, so we will test to see if it is a bare
            // repository...
            List<String> paths = new ArrayList<String>();
            paths.add( origin.getPath() );
            paths.add( pathJoin( origin.getPath(), ".git" ) );

            for ( String path : paths ) {
                try {
                    is_bare = isBareRepository(path);
                    break;// we can break already if we don't have an exception
                } catch (GitException e) { }
            }
        }

        if ( ! is_bare ) {
            try {
                List<IndexEntry> submodules = getSubmodules("HEAD");

                for (IndexEntry submodule : submodules) {
                    // First fix the URL to the submodule inside the super-project
                    String sUrl = pathJoin( origin.getPath(), submodule.getFile() );
                    setSubmoduleUrl( submodule.getFile(), sUrl );

                    // Second, if the submodule already has been cloned, fix its own
                    // url...
                    String subGitDir = pathJoin( submodule.getFile(), ".git" );

                    /* it is possible that the submodule does not exist yet
                     * since we wait until after checkout to do 'submodule
                     * udpate' */
                    if ( hasGitRepo( subGitDir ) ) {
                        if (! "".equals( getRemoteUrl("origin", subGitDir) )) {
                            setRemoteUrl("origin", sUrl, subGitDir);
                        }
                    }
                }
            } catch (GitException e) {
                // this can fail for example HEAD doesn't exist yet
            }
        } else {
           // we've made a reasonable attempt to detect whether the origin is
           // non-bare, so we'll just assume it is bare from here on out and
           // thus the URLs are correct as given by (which is default behavior)
           //    git config --get submodule.NAME.url
        }
    }

    /**
     * Set up submodule URLs so that they correspond to the remote pertaining to
     * the revision that has been checked out.
     */
    public void setupSubmoduleUrls( Revision rev, TaskListener listener ) throws GitException, InterruptedException {
        String remote = null;

        // try to locate the remote repository from where this commit came from
        // (by using the heuristics that the branch name, if available, contains the remote name)
        // if we can figure out the remote, the other setupSubmoduleUrls method
        // look at its URL, and if it's a non-bare repository, we attempt to retrieve modules
        // from this checked out copy.
        //
        // the idea is that you have something like tree-structured repositories: at the root you have corporate central repositories that you
        // ultimately push to, which all .gitmodules point to, then you have intermediate team local repository,
        // which is assumed to be a non-bare repository (say, a checked out copy on a shared server accessed via SSH)
        //
        // the abovementioned behaviour of the Git plugin makes it pick up submodules from this team local repository,
        // not the corporate central.
        //
        // (Kohsuke: I have a bit of hesitation/doubt about such a behaviour change triggered by seemingly indirect
        // evidence of whether the upstream is bare or not (not to mention the fact that you can't reliably
        // figure out if the repository is bare or not just from the URL), but that's what apparently has been implemented
        // and we care about the backward compatibility.)
        //
        // note that "figuring out which remote repository the commit came from" isn't a well-defined
        // question, and this is really a heuristics. The user might be telling us to build a specific SHA1.
        // or maybe someone pushed directly to the workspace and so it may not correspond to any remote branch.
        // so if we fail to figure this out, we back out and avoid being too clever. See JENKINS-10060 as an example
        // of where our trying to be too clever here is breaking stuff for people.
        for (Branch br : rev.getBranches()) {
            String b = br.getName();
            if (b != null) {
                int slash = b.indexOf('/');

                if ( slash != -1 )
                    remote = getDefaultRemote( b.substring(0,slash) );
            }

            if (remote!=null)   break;
        }

        if (remote==null)
            remote = getDefaultRemote();

        if (remote!=null)
            setupSubmoduleUrls( remote, listener );
    }

    public void tag(String tagName, String comment) throws GitException, InterruptedException {
        tagName = tagName.replace(' ', '_');
        try {
            launchCommand("tag", "-a", "-f", "-m", comment, tagName);
        } catch (GitException e) {
            throw new GitException("Could not apply tag " + tagName, e);
        }
    }

    public void appendNote(String note, String namespace ) throws GitException, InterruptedException {
        createNote(note,namespace,"append");
    }

    public void addNote(String note, String namespace ) throws GitException, InterruptedException {
        createNote(note,namespace,"add");
    }

    private void createNote(String note, String namespace, String command ) throws GitException, InterruptedException {
        File msg = null;
        try {
            msg = File.createTempFile("git-note", "txt", workspace);
            FileUtils.writeStringToFile(msg,note);
            launchCommand("notes", "--ref=" + namespace, command, "-F", msg.getAbsolutePath());
        } catch (IOException e) {
            throw new GitException("Could not apply note " + note, e);
        } catch (GitException e) {
            throw new GitException("Could not apply note " + note, e);
        } finally {
            if (msg!=null)
                msg.delete();
        }
    }

    /**
     * Launch command using the workspace as working directory
     * @param args
     * @return command output
     * @throws GitException
     */
    public String launchCommand(ArgumentListBuilder args) throws GitException, InterruptedException {
        return launchCommandIn(args, workspace);
    }

    /**
     * Launch command using the workspace as working directory
     * @param args
     * @return command output
     * @throws GitException
     */
    public String launchCommand(String... args) throws GitException, InterruptedException {
        return launchCommand(new ArgumentListBuilder(args));
    }

    private String launchCommandWithCredentials(ArgumentListBuilder args, File workDir,
                                                StandardCredentials credentials,
                                                @NonNull String url) throws GitException, InterruptedException {
        try {
            return launchCommandWithCredentials(args, workDir, credentials, new URIish(url));
        } catch (URISyntaxException e) {
            throw new GitException("Invalid URL " + url);
        }
    }

    private String launchCommandWithCredentials(ArgumentListBuilder args, File workDir,
                                                StandardCredentials credentials,
                                                @NonNull URIish url) throws GitException, InterruptedException {

        File key = null;
        File ssh = null;
        File pass = null;
        File store = null;
        EnvVars env = environment;
        try {
            if (credentials != null && credentials instanceof SSHUserPrivateKey) {
                SSHUserPrivateKey sshUser = (SSHUserPrivateKey) credentials;
                listener.getLogger().println("using GIT_SSH to set credentials " + sshUser.getDescription());

                key = createSshKeyFile(key, sshUser);
                if (launcher.isUnix()) {
                    ssh =  createUnixGitSSH(key);
                    pass =  createUnixSshAskpass(sshUser);
                } else {
                    ssh =  createWindowsGitSSH(key);
                    pass =  createWindowsSshAskpass(sshUser);
                }

                env = new EnvVars(env);
                env.put("GIT_SSH", ssh.getAbsolutePath());
                env.put("SSH_ASKPASS", pass.getAbsolutePath());
            }

            if ("http".equalsIgnoreCase(url.getScheme()) || "https".equalsIgnoreCase(url.getScheme())) {
                checkCredentials(url, credentials);

                if (credentials != null) {
                    listener.getLogger().println("using .gitcredentials to set credentials");

                    String urlWithCredentials = getGitCredentialsURL(url, credentials);
                    store = createGitCredentialsStore(urlWithCredentials);
                    String fileStore = launcher.isUnix() ? store.getAbsolutePath() : "\\\"" + store.getAbsolutePath() + "\\\"";
                    launchCommandIn(workDir, "config", "--local", "credential.helper", "store --file=" + fileStore);
                }
            }

            return launchCommandIn(args, workDir, env);
        } catch (IOException e) {
            throw new GitException("Failed to setup credentials", e);
        } finally {
            if (pass != null) pass.delete();
            if (key != null) key.delete();
            if (ssh != null) ssh.delete();
            if (store != null) {
                store.delete();
                try {
                    launchCommandIn(workDir, "config", "--local", "--remove-section", "credential");
                } catch (GitException e) {
                    listener.getLogger().println("Could not remove the credential section from the git configuration");
                }
            }
        }
    }

    private File createGitCredentialsStore(String urlWithCredentials) throws IOException {
        File store = File.createTempFile("git", ".credentials");
        PrintWriter w = new PrintWriter(store);
        w.print(urlWithCredentials);
        w.flush();
        w.close();
        return store;
    }

    private File createSshKeyFile(File key, SSHUserPrivateKey sshUser) throws IOException, InterruptedException {
        key = File.createTempFile("ssh", "key");
        PrintWriter w = new PrintWriter(key);
        List<String> privateKeys = SlaveComputer.getChannelToMaster().call(new CliGitAPIImpl.GetPrivateKeys(sshUser));
        for (String s : privateKeys) {
            w.println(s);
        }
        w.close();
        new FilePath(key).chmod(0400);
        return key;
    }

    private File createWindowsSshAskpass(SSHUserPrivateKey sshUser) throws IOException {
        File ssh = File.createTempFile("pass", ".bat");
        PrintWriter w = new PrintWriter(ssh);
        w .println("echo \"" + Secret.toString(sshUser.getPassphrase()) + "\"");
        w.flush();
        w.close();
        ssh.setExecutable(true);
        return ssh;
    }

    private File createUnixSshAskpass(SSHUserPrivateKey sshUser) throws IOException {
        File ssh = File.createTempFile("pass", ".sh");
        PrintWriter w = new PrintWriter(ssh);
        w.println("#!/bin/sh");
        w.println("/bin/echo \"" + Secret.toString(sshUser.getPassphrase()) + "\"");
        w.close();
        ssh.setExecutable(true);
        return ssh;
    }


    private File createWindowsGitSSH(File key) throws IOException {
        File ssh = File.createTempFile("ssh", ".bat");
        // windows git installer place C:\Program Files\Git\cmd\git.exe in PATH

        String progFiles = System.getenv("ProgramFiles");
        String sshPath = "/Git/bin/ssh.exe";
        File sshexe = new File(progFiles + sshPath);
        if (!sshexe.exists()) {
          // try the (x86) version for 64-bit Windows builds
          sshexe = new File(progFiles + " (x86)" + sshPath);
        }

        if (!sshexe.exists()) {
            throw new RuntimeException("git plugin only support official git client http://git-scm.com/download/win");
        }
        PrintWriter w = new PrintWriter(ssh);
        w .println("@echo off");
        w .println("\"" + sshexe.getAbsolutePath() + "\" -i \"" + key.getAbsolutePath() +"\" -o StrictHostKeyChecking=no %* ");
        w.flush();
        w.close();
        ssh.setExecutable(true);
        return ssh;
    }

    private File createUnixGitSSH(File key) throws IOException {
        File ssh = File.createTempFile("ssh", ".sh");
        PrintWriter w = new PrintWriter(ssh);
        w.println("#!/bin/sh");
        w.println("ssh -i \"" + key.getAbsolutePath() + "\" -o StrictHostKeyChecking=no \"$@\"");
        w.close();
        ssh.setExecutable(true);
        return ssh;
    }

    private String launchCommandIn(File workDir, String... args) throws GitException, InterruptedException {
        return launchCommandIn(new ArgumentListBuilder(args), workDir);
    }

    private String launchCommandIn(ArgumentListBuilder args, File workDir) throws GitException, InterruptedException {
        return launchCommandIn(args, workDir, environment);
    }

    private String launchCommandIn(ArgumentListBuilder args, File workDir, EnvVars env) throws GitException, InterruptedException {
        ByteArrayOutputStream fos = new ByteArrayOutputStream();
        // JENKINS-13356: capture the output of stderr separately
        ByteArrayOutputStream err = new ByteArrayOutputStream();

        EnvVars environment = new EnvVars(env);
        if (!env.containsKey("SSH_ASKPASS")) {
            // GIT_ASKPASS supersed SSH_ASKPASS when set, so don't mask SSH passphrase when set
            environment.put("GIT_ASKPASS", launcher.isUnix() ? "/bin/echo" : "echo ");
        }
        String command = "git " + StringUtils.join(args.toCommandArray(), " ");
        try {
            args.prepend(gitExe);
            Launcher.ProcStarter p = launcher.launch().cmds(args.toCommandArray()).
                    envs(environment).stdout(fos).stderr(err);
            if (workDir != null) p.pwd(workDir);
            int status = p.start().joinWithTimeout(TIMEOUT, TimeUnit.MINUTES, listener);

            String result = fos.toString();
            if (status != 0) {
                throw new GitException("Command \""+command+"\" returned status code " + status + ":\nstdout: " + result + "\nstderr: "+ err.toString());
            }

            return result;
        } catch (GitException e) {
            throw e;
        } catch (IOException e) {
            throw new GitException("Error performing command: " + command, e);
        } catch (Throwable t) {
            throw new GitException("Error performing git command", t);
        }

    }

    public void push(URIish url, String refspec) throws GitException, InterruptedException {
        ArgumentListBuilder args = new ArgumentListBuilder();
        args.add("push", url.toPrivateASCIIString());

        if (refspec != null) {
            args.add(refspec);
        }

        StandardCredentials cred = credentials.get(url.toPrivateString());
        if (cred == null) cred = defaultCredentials;
        launchCommandWithCredentials(args, workspace, cred, url);
        // Ignore output for now as there's many different formats
        // That are possible.
    }

    public void push(String remoteName, String refspec) throws GitException, InterruptedException {
        String url = getRemoteUrl(remoteName);
        if (url == null) {
            throw new GitException("bad remote name, URL not set in working copy");
        }

        try {
            push(new URIish(url), refspec);
        } catch (URISyntaxException e) {
            throw new GitException("bad repository URL", e);
        }
    }

    protected Set<Branch> parseBranches(String fos) throws GitException, InterruptedException {
        // TODO: git branch -a -v --abbrev=0 would do this in one shot..

        Set<Branch> branches = new HashSet<Branch>();

        BufferedReader rdr = new BufferedReader(new StringReader(fos));
        String line;
        try {
            while ((line = rdr.readLine()) != null) {
                // Ignore the 1st
                line = line.substring(2);
                // Ignore '(no branch)' or anything with " -> ", since I think
                // that's just noise
                if ((!line.startsWith("("))
                    && (line.indexOf(" -> ") == -1)) {
                    branches.add(new Branch(line, revParse(line)));
                }
            }
        } catch (IOException e) {
            throw new GitException("Error parsing branches", e);
        }

        return branches;
    }

    public Set<Branch> getBranches() throws GitException, InterruptedException {
        return parseBranches(launchCommand("branch", "-a"));
    }

    public Set<Branch> getRemoteBranches() throws GitException, InterruptedException {
        Repository db = getRepository();
        try {
            Map<String, Ref> refs = db.getAllRefs();
            Set<Branch> branches = new HashSet<Branch>();

            for(Ref candidate : refs.values()) {
                if(candidate.getName().startsWith(Constants.R_REMOTES)) {
                    Branch buildBranch = new Branch(candidate);
                    if (!GitClient.quietRemoteBranches) {
                        listener.getLogger().println("Seen branch in repository " + buildBranch.getName());
                    }
                    branches.add(buildBranch);
                }
            }

            if (branches.size() == 1) {
                listener.getLogger().println("Seen 1 remote branch");
            } else {
                listener.getLogger().println(MessageFormat.format("Seen {0} remote branches", branches.size()));
            }

            return branches;
        } finally {
            db.close();
        }
    }

    public void checkout(String commit) throws GitException, InterruptedException {
        launchCommand("checkout", "-f", commit);
    }

    public void checkout(String ref, String branch) throws GitException, InterruptedException {
        launchCommand("checkout", "-b", branch, ref);
    }

    public void checkoutBranch(String branch, String ref) throws GitException, InterruptedException {
        try {
            // First, checkout to detached HEAD, so we can delete the branch.
            checkout(ref);

            if (branch!=null) {
                // Second, check to see if the branch actually exists, and then delete it if it does.
                for (Branch b : getBranches()) {
                    if (b.getName().equals(branch)) {
                        deleteBranch(branch);
                    }
                }
                // Lastly, checkout the branch, creating it in the process, using commitish as the start point.
                checkout(ref, branch);
            }
        } catch (GitException e) {
            if (Pattern.compile("index\\.lock").matcher(e.getMessage()).find()) {
                throw new GitLockFailedException("Could not lock repository. Please try again", e);
            } else {
                throw new GitException("Could not checkout " + branch + " with start point " + ref, e);
            }
        }
    }

    public boolean tagExists(String tagName) throws GitException, InterruptedException {
        return launchCommand("tag", "-l", tagName).trim().equals(tagName);
    }

    public void deleteBranch(String name) throws GitException, InterruptedException {
        try {
            launchCommand("branch", "-D", name);
        } catch (GitException e) {
            throw new GitException("Could not delete branch " + name, e);
        }

    }


    public void deleteTag(String tagName) throws GitException, InterruptedException {
        tagName = tagName.replace(' ', '_');
        try {
            launchCommand("tag", "-d", tagName);
        } catch (GitException e) {
            throw new GitException("Could not delete tag " + tagName, e);
        }
    }

    public List<IndexEntry> lsTree(String treeIsh, boolean recursive) throws GitException, InterruptedException {
        List<IndexEntry> entries = new ArrayList<IndexEntry>();
        String result = launchCommand("ls-tree", recursive?"-r":null, treeIsh);

        BufferedReader rdr = new BufferedReader(new StringReader(result));
        String line;
        try {
            while ((line = rdr.readLine()) != null) {
                String[] entry = line.split("\\s+");
                entries.add(new IndexEntry(entry[0], entry[1], entry[2],
                                           entry[3]));
            }
        } catch (IOException e) {
            throw new GitException("Error parsing ls tree", e);
        }

        return entries;
    }

    public List<ObjectId> revListAll() throws GitException, InterruptedException {
        return doRevList("--all");
    }

    public List<ObjectId> revList(String ref) throws GitException, InterruptedException {
        return doRevList(ref);
    }

    private List<ObjectId> doRevList(String... extraArgs) throws GitException, InterruptedException {
        List<ObjectId> entries = new ArrayList<ObjectId>();
        ArgumentListBuilder args = new ArgumentListBuilder("rev-list");
        args.add(extraArgs);
        String result = launchCommand(args);
        BufferedReader rdr = new BufferedReader(new StringReader(result));
        String line;

        try {
            while ((line = rdr.readLine()) != null) {
                // Add the SHA1
                entries.add(ObjectId.fromString(line));
            }
        } catch (IOException e) {
            throw new GitException("Error parsing rev list", e);
        }

        return entries;
    }

    public boolean isCommitInRepo(ObjectId commit) throws InterruptedException {
        try {
            List<ObjectId> revs = revList(commit.name());

            if (revs.size() == 0) {
                return false;
            } else {
                return true;
            }
        } catch (GitException e) {
            return false;
        }
    }

    public void add(String filePattern) throws GitException, InterruptedException {
        try {
            launchCommand("add", filePattern);
        } catch (GitException e) {
            throw new GitException("Cannot add " + filePattern, e);
        }
    }

    public void branch(String name) throws GitException, InterruptedException {
        try {
            launchCommand("branch", name);
        } catch (GitException e) {
            throw new GitException("Cannot create branch " + name, e);
        }
    }

    public void commit(String message) throws GitException, InterruptedException {
        File f = null;
        try {
            f = File.createTempFile("gitcommit", ".txt");
            FileOutputStream fos = null;
            try {
                fos = new FileOutputStream(f);
                fos.write(message.getBytes());
            } finally {
                if (fos != null)
                    fos.close();
            }
            launchCommand("commit", "-F", f.getAbsolutePath());

        } catch (GitException e) {
            throw new GitException("Cannot commit " + message, e);
        } catch (FileNotFoundException e) {
            throw new GitException("Cannot commit " + message, e);
        } catch (IOException e) {
            throw new GitException("Cannot commit " + message, e);
        } finally {
            if (f != null) f.delete();
        }
    }

    public void addCredentials(String url, StandardCredentials credentials) {
        this.credentials.put(url, credentials);
    }

    public void clearCredentials() {
        this.credentials.clear();
    }

    public void addDefaultCredentials(StandardCredentials credentials) {
        this.defaultCredentials = credentials;
    }

    public void setAuthor(String name, String email) throws GitException {
        env("GIT_AUTHOR_NAME", name);
        env("GIT_AUTHOR_EMAIL", email);
    }

    public void setCommitter(String name, String email) throws GitException {
        env("GIT_COMMITTER_NAME", name);
        env("GIT_COMMITTER_EMAIL", email);
    }

    private void env(String name, String value) {
        if (value==null)    environment.remove(name);
        else                environment.put(name,value);
    }

    public Repository getRepository() throws GitException {
        try {
            return FileRepositoryBuilder.create(new File(workspace, Constants.DOT_GIT));
        } catch (IOException e) {
            throw new GitException("Failed to open Git repository " + workspace, e);
        }
    }

    public FilePath getWorkTree() {
        return new FilePath(workspace);
    }

    public Set<String> getTagNames(String tagPattern) throws GitException {
        try {
            ArgumentListBuilder args = new ArgumentListBuilder();
            args.add("tag", "-l", tagPattern);

            String result = launchCommandIn(args, workspace);

            Set<String> tags = new HashSet<String>();
            BufferedReader rdr = new BufferedReader(new StringReader(result));
            String tag;
            while ((tag = rdr.readLine()) != null) {
                // Add the SHA1
                tags.add(tag);
            }
            return tags;
        } catch (Exception e) {
            throw new GitException("Error retrieving tag names", e);
        }
    }

    public String getTagMessage(String tagName) throws GitException, InterruptedException {
        // 10000 lines of tag message "ought to be enough for anybody"
        String out = launchCommand("tag", "-l", tagName, "-n10000");
        // Strip the leading four spaces which git prefixes multi-line messages with
        return out.substring(tagName.length()).replaceAll("(?m)(^    )", "").trim();
    }

    public Map<String, ObjectId> getHeadRev(String url) throws GitException, InterruptedException {
        ArgumentListBuilder args = new ArgumentListBuilder("ls-remote");
        args.add("-h");
        args.add(url);

        StandardCredentials cred = credentials.get(url);
        if (cred == null) cred = defaultCredentials;

        String result = launchCommandWithCredentials(args, null, cred, url);

        Map<String, ObjectId> heads = new HashMap<String, ObjectId>();
        String[] lines = result.split("\n");
        for (String line : lines) {
            if (line.length() < 41) throw new GitException("unexpected ls-remote output " + line);
            heads.put(line.substring(41), ObjectId.fromString(result.substring(0, 40)));
        }
        return heads;
    }

    public ObjectId getHeadRev(String url, String branch) throws GitException, InterruptedException {
        String[] branchExploded = branch.split("/");
        branch = branchExploded[branchExploded.length-1];
        ArgumentListBuilder args = new ArgumentListBuilder("ls-remote");
        args.add("-h");

        StandardCredentials cred = credentials.get(url);
        if (cred == null) cred = defaultCredentials;

        args.add(url);
        args.add(branch);
        String result = launchCommandWithCredentials(args, null, cred, url);
        return result.length()>=40 ? ObjectId.fromString(result.substring(0, 40)) : null;
    }


    //
    //
    // Legacy Implementation of IGitAPI
    //
    //

    @Deprecated
    public void merge(String refSpec) throws GitException, InterruptedException {
        try {
            launchCommand("merge", refSpec);
        } catch (GitException e) {
            throw new GitException("Could not merge " + refSpec, e);
        }
    }



    @Deprecated
    public void push(RemoteConfig repository, String refspec) throws GitException, InterruptedException {
        ArgumentListBuilder args = new ArgumentListBuilder();
        URIish uri = repository.getURIs().get(0);
        String url = uri.toPrivateString();
        StandardCredentials cred = credentials.get(url);
        if (cred == null) cred = defaultCredentials;

        args.add("push", url);

        if (refspec != null)
            args.add(refspec);

        launchCommandWithCredentials(args, workspace, cred, uri);
        // Ignore output for now as there's many different formats
        // That are possible.

    }

    @Deprecated
    public List<Branch> getBranchesContaining(String revspec) throws GitException, InterruptedException {
        return new ArrayList<Branch>(parseBranches(launchCommand("branch", "-a", "--contains", revspec)));
    }

    @Deprecated
    public ObjectId mergeBase(ObjectId id1, ObjectId id2) throws InterruptedException {
        try {
            String result;
            try {
                result = launchCommand("merge-base", id1.name(), id2.name());
            } catch (GitException ge) {
                return null;
            }


            BufferedReader rdr = new BufferedReader(new StringReader(result));
            String line;

            while ((line = rdr.readLine()) != null) {
                // Add the SHA1
                return ObjectId.fromString(line);
            }
        } catch (IOException e) {
            throw new GitException("Error parsing merge base", e);
        } catch (GitException e) {
            throw new GitException("Error parsing merge base", e);
        }

        return null;
    }

    @Deprecated
    public String getAllLogEntries(String branch) throws InterruptedException {
        // BROKEN: --all and branch are conflicting.
        return launchCommand("log", "--all", "--pretty=format:'%H#%ct'", branch);
    }

    /**
     * Compute the URL to be used by <a href="https://www.kernel.org/pub/software/scm/git/docs/git-credential-store.html">git-credentials-store</a>
     */
    private String getGitCredentialsURL(String url, StandardCredentials cred) {
        try {
            return getGitCredentialsURL(new URIish(url), cred);
        } catch (URISyntaxException e) {
            throw new GitException("invalid repository URL " + url, e);
        }
    }

    private String getGitCredentialsURL(URIish u, StandardCredentials cred) {
        String scheme = u.getScheme();
        // gitcredentials format is sheme://user:password@hostname
        URIish uri = new URIish()
            .setScheme(scheme)
            .setUser(u.getUser())
            .setPass(u.getPass())
            .setHost(u.getHost())
            .setPort(u.getPort());

        if (cred != null && cred instanceof UsernamePasswordCredentialsImpl) {
            UsernamePasswordCredentialsImpl up = (UsernamePasswordCredentialsImpl) cred;
            uri = uri.setUser(up.getUsername())
                     .setPass(Secret.toString(up.getPassword()));
        }

        // use toPrivateString to include the password too
        return uri.toPrivateString();
    }

    /**
     * Check credentials are valid to access the remote repository (avoids git to interactively request username/password.)
     */
    private void checkCredentials(URIish u, StandardCredentials cred) {
        String url = u.toPrivateString();
        HttpClient client = new HttpClient();
        Credentials defaultcreds;
        if (cred != null && cred instanceof UsernamePasswordCredentialsImpl) {
            UsernamePasswordCredentialsImpl up = (UsernamePasswordCredentialsImpl) cred;
            defaultcreds = new UsernamePasswordCredentials(up.getUsername(), Secret.toString(up.getPassword()));
        } else if (u.getUser() != null && u.getPass() != null) {
            defaultcreds = new UsernamePasswordCredentials(u.getUser(), u.getPass());
        } else {
            defaultcreds = Netrc.getInstance().getCredentials(u.getHost());
        }
        if (defaultcreds != null) {
            client.getParams().setAuthenticationPreemptive(true);
            client.getState().setCredentials(AuthScope.ANY, defaultcreds);
        }

        if (proxy != null) {
        	boolean shouldProxy = true;
            for(Pattern p : proxy.getNoProxyHostPatterns()) {
                if(p.matcher(u.getHost()).matches()) {
                    shouldProxy = false;
                    break;
                }
            }
            if(shouldProxy) {
                client.getHostConfiguration().setProxy(proxy.name, proxy.port);
                if (proxy.getUserName() != null && proxy.getPassword() != null)
                    client.getState().setProxyCredentials(AuthScope.ANY, new UsernamePasswordCredentials(proxy.getUserName(), proxy.getPassword()));
            }
        }

        List<String> candidates = new ArrayList<String>();
        candidates.add(url + "/info/refs"); // dump-http
        candidates.add(url + "/info/refs?service=git-upload-pack"); // smart-http
        if (!url.endsWith(".git")) {
            candidates.add(url + ".git/info/refs"); // dump-http
            candidates.add(url + ".git/info/refs?service=git-upload-pack"); // smart-http
        }

        int status = 0;
        try {
            for (String candidate : candidates) {
                GetMethod get = new GetMethod(candidate);
                get.setFollowRedirects(true);
                get.getParams().setParameter(USER_AGENT, "git/1.7.0");
                status = client.executeMethod(get);
                if (status == 200) break;
            }

            if (status != 200)
                throw new GitException("Failed to connect to " + u.toString()
                    + (cred != null ? " using credentials " + cred.getDescription() : "" )
                    + " (status = "+status+")");
        } catch (SSLException e) {
            throw new GitException(e.getLocalizedMessage());
        } catch (IOException e) {
            throw new GitException("Failed to connect to " + u.toString()
                    + (cred != null ? " using credentials " + cred.getDescription() : "" ));
        } catch (IllegalArgumentException e) {
            throw new GitException("Invalid URL " + u.toString());
        }
    }

    private static class GetPrivateKeys implements Callable<List<String>, RuntimeException> {
        private final SSHUserPrivateKey sshUser;

        public GetPrivateKeys(SSHUserPrivateKey sshUser) {
            this.sshUser = sshUser;
        }

        public List<String> call() throws RuntimeException {
            return sshUser.getPrivateKeys();
        }
    }

    /**
     * preventive Time-out for git command execution.
     * <p>
     * We run git as an external process so can't guarantee it won't hang for whatever reason. Even plugin does its
     * best to avoid git interactively asking for credentials, but there's a bunch of other cases git may hung.
     */
    public static int TIMEOUT = Integer.getInteger(Git.class.getName() + ".timeOut", 10);
}
