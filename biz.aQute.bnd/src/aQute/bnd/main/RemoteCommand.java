package aQute.bnd.main;

import java.io.File;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map.Entry;
import java.util.Set;
import java.util.jar.Attributes;
import java.util.jar.Manifest;

import org.osgi.framework.namespace.BundleNamespace;
import org.osgi.framework.namespace.HostNamespace;
import org.osgi.framework.namespace.IdentityNamespace;
import org.osgi.framework.namespace.PackageNamespace;
import org.osgi.framework.wiring.dto.BundleRevisionDTO;
import org.osgi.resource.dto.CapabilityDTO;
import org.osgi.service.repository.ContentNamespace;
import org.yaml.snakeyaml.Yaml;

import aQute.bnd.header.Attrs;
import aQute.bnd.header.Parameters;
import aQute.bnd.osgi.Constants;
import aQute.bnd.osgi.Jar;
import aQute.bnd.osgi.Processor;
import aQute.bnd.osgi.Verifier;
import aQute.bnd.osgi.resource.CapabilityBuilder;
import aQute.bnd.version.Version;
import aQute.lib.converter.Converter;
import aQute.lib.converter.TypeReference;
import aQute.lib.getopt.Arguments;
import aQute.lib.getopt.Description;
import aQute.lib.getopt.Options;
import aQute.remote.api.Agent;
import aQute.remote.api.Event;
import aQute.remote.api.Supervisor;
import aQute.remote.util.AgentSupervisor;

class RemoteCommand extends Processor {
	private static TypeReference<List<Version>>	tref				= new TypeReference<List<Version>>() {};
	private Yaml				y					= new Yaml();
	private bnd					bnd;
	private LauncherSupervisor	launcher			= new LauncherSupervisor();
	private Agent				agent;
	private int					port;
	private String				host;
	private static Set<String>	IGNORED_NAMESPACES	= new HashSet<>();

	static {
		IGNORED_NAMESPACES.add(PackageNamespace.PACKAGE_NAMESPACE); // handled
																	// specially
		IGNORED_NAMESPACES.add(HostNamespace.HOST_NAMESPACE);
		IGNORED_NAMESPACES.add(BundleNamespace.BUNDLE_NAMESPACE);
		IGNORED_NAMESPACES.add(IdentityNamespace.IDENTITY_NAMESPACE);
		IGNORED_NAMESPACES.add(ContentNamespace.CONTENT_NAMESPACE);
	}

	/**
	 * This is the supervisor on the bnd launcher side. It provides the SHA
	 * repository for the agent and handles the redirection. It also handles the
	 * events.
	 */
	class LauncherSupervisor extends AgentSupervisor<Supervisor,Agent>implements Supervisor {

		@Override
		public boolean stdout(String out) throws Exception {
			System.out.print(out);
			return true;
		}

		@Override
		public boolean stderr(String out) throws Exception {
			System.err.print(out);
			return true;
		}

		public void connect(String host, int port) throws Exception {
			super.connect(Agent.class, this, host, port);
		}

		@Override
		public void event(Event e) throws Exception {
			System.out.println(e);
		}

	}

	@Description("Communicates with the remote agent")
	interface RemoteOptions extends Options {
		@Description("Specify the host to commicate with, default is 'localhost'")
		String host(String deflt);

		@Description("Specify the port to commicate with, default is " + Agent.DEFAULT_PORT)
		int port(int deflt);
	}

	RemoteCommand(bnd bnd, RemoteOptions options) throws Exception {
		super(bnd);
		this.bnd = bnd;
		use(bnd);
		launcher = new LauncherSupervisor();
		launcher.connect(host = options.host("localhost"), port = options.port(Agent.DEFAULT_PORT));
		agent = launcher.getAgent();
	}

	public void close() throws IOException {
		launcher.close();
	}

	@Description("Get the framework info")
	@Arguments(arg = {})
	interface FrameworkOptions extends Options {}

	public void _framework(FrameworkOptions opts) throws Exception {
		dump(agent.getFramework());
	}

	@Description("Get the bundle revisions")
	@Arguments(arg = {
			"bundleid..."
	})
	interface RevisonOptions extends Options {}

	public void _revisions(RevisonOptions opts) throws Exception {
		long[] ids = Converter.cnv(long[].class, opts._arguments());
		dump(agent.getBundleRevisons(ids));
	}

	@Description("Ping the remote framework")
	@Arguments(arg = {})
	interface PingOptions extends Options {}

	public void _ping(PingOptions opts) throws Exception {
		long start = System.currentTimeMillis();
		if (agent.ping())
			bnd.out.println("Ok " + (System.currentTimeMillis() - start) + "ms");
		else
			bnd.out.println("Could not reach " + host + ":" + port);
	}

	/**
	 * Create a distro from a remote agent
	 */

	@Description("Create a distro jar from a remote agent")
	@Arguments(arg = {
			"bsn", "[version]"
	})
	interface DistroOptions extends Options {
		String vendor();

		String description();

		String copyright();

		String license();

		String extra();

		String output(String deflt);
	}

	public void _distro(DistroOptions opts) throws Exception {
		List<String> arts = opts._arguments();
		String bsn;
		String version;

		bsn = arts.remove(0);

		if (!Verifier.isBsn(bsn)) {
			error("Not a bundle symbolic name %s", bsn);
		}

		if (arts.isEmpty())
			version = "0";
		else {
			version = arts.remove(0);
			if (!Version.isVersion(version)) {
				error("Invalid version %s", version);
			}
		}

		File output = getFile(opts.output("distro.jar"));
		if (output.getParentFile() == null || !output.getParentFile().isDirectory()) {
			error("Cannot write to %s because parent not a directory", output);
		}

		if (output.isFile() && !output.canWrite()) {
			error("Cannot write to ", output);
		}

		bnd.trace("Starting distro %s;%s", bsn, version);

		List<BundleRevisionDTO> bundleRevisons = agent.getBundleRevisons();
		trace("Found %s bundle revisions", bundleRevisons.size());

		Parameters packages = new Parameters();
		Parameters provided = new Parameters();

		for (BundleRevisionDTO brd : bundleRevisons) {
			trace("Found %s bundle revisions", bundleRevisons.size());
			for (CapabilityDTO c : brd.capabilities) {
				CapabilityBuilder crb = new CapabilityBuilder(c.namespace);

				//
				// We need to fixup versions :-(
				// Versions are encoded as strings in DTOs
				// and that means we need to treat the version key
				// special
				//

				for (Entry<String,Object> e : c.attributes.entrySet()) {
					String key = e.getKey();
					Object value = e.getValue();

					if (key.equals("version")) {
						if (value instanceof Collection || value.getClass().isArray())
							value = Converter.cnv(tref, value);
						else
							value = new Version((String) value);
					}
					crb.addAttribute(key, value);
				}
				crb.addDirectives(c.directives);


				Attrs attrs = crb.toAttrs();

				if (crb.isPackage()) {
					attrs.remove(Constants.BUNDLE_SYMBOLIC_NAME_ATTRIBUTE);
					attrs.remove(Constants.BUNDLE_VERSION_ATTRIBUTE);
					String pname = attrs.remove(PackageNamespace.PACKAGE_NAMESPACE);
					if (pname == null) {
						warning("Invalid package capability found %s", c);
					} else
						packages.put(pname, attrs);
					trace("P: %s;%s", pname, attrs);
				} else if (!IGNORED_NAMESPACES.contains(c.namespace)) {
					trace("C %s;%s", c.namespace, attrs);
					provided.put(c.namespace, attrs);
				}
			}
		}

		if (isOk()) {
			Manifest m = new Manifest();
			Attributes main = m.getMainAttributes();

			main.putValue(Constants.BUNDLE_MANIFESTVERSION, "2");

			main.putValue(Constants.BUNDLE_SYMBOLICNAME, bsn);
			main.putValue(Constants.BUNDLE_VERSION, version);

			main.putValue(Constants.EXPORT_PACKAGE, packages.toString());
			main.putValue(Constants.PROVIDE_CAPABILITY, provided.toString());

			if (opts.description() != null)
				main.putValue(Constants.BUNDLE_DESCRIPTION, opts.description());
			if (opts.license() != null)
				main.putValue(Constants.BUNDLE_LICENSE, opts.license());
			if (opts.copyright() != null)
				main.putValue(Constants.BUNDLE_COPYRIGHT, opts.copyright());
			if (opts.vendor() != null)
				main.putValue(Constants.BUNDLE_VENDOR, opts.vendor());

			Jar jar = new Jar("distro");
			jar.setManifest(m);

			Verifier v = new Verifier(jar);
			v.setProperty("-fixupmessages",
					"osgi* namespaces must not be specified with generic requirements/capabilities");
			v.verify();
			v.getErrors();

			if (isFailOk() || v.isOk())
				jar.write(output);
			else
				getInfo(v);
		}

	}

	private void dump(Object o) {
		y.dump(o, new OutputStreamWriter(bnd.out));
	}

}
