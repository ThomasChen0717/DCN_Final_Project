package edu.brown.cs.sdn.apps.sps;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import edu.brown.cs.sdn.apps.util.Host;

import net.floodlightcontroller.core.IFloodlightProviderService;
import net.floodlightcontroller.core.IOFSwitch;
import net.floodlightcontroller.core.IOFSwitch.PortChangeType;
import net.floodlightcontroller.core.IOFSwitchListener;
import net.floodlightcontroller.core.ImmutablePort;
import net.floodlightcontroller.core.module.FloodlightModuleContext;
import net.floodlightcontroller.core.module.FloodlightModuleException;
import net.floodlightcontroller.core.module.IFloodlightModule;
import net.floodlightcontroller.core.module.IFloodlightService;
import net.floodlightcontroller.devicemanager.IDevice;
import net.floodlightcontroller.devicemanager.IDeviceListener;
import net.floodlightcontroller.devicemanager.IDeviceService;
import net.floodlightcontroller.linkdiscovery.ILinkDiscoveryListener;
import net.floodlightcontroller.linkdiscovery.ILinkDiscoveryService;
import net.floodlightcontroller.routing.Link;

public class ShortestPathSwitching implements IFloodlightModule, IOFSwitchListener, 
		ILinkDiscoveryListener, IDeviceListener, InterfaceShortestPathSwitching
{
	public static final String MODULE_NAME = ShortestPathSwitching.class.getSimpleName();
	
	// Interface to the logging system
    private static Logger log = LoggerFactory.getLogger(MODULE_NAME);
    
    // Interface to Floodlight core for interacting with connected switches
    private IFloodlightProviderService floodlightProv;

    // Interface to link discovery service
    private ILinkDiscoveryService linkDiscProv;

    // Interface to device manager service
    private IDeviceService deviceProv;
    
    // Switch table in which rules should be installed
    private byte table;
    
    // Map of hosts to devices
    private Map<IDevice,Host> knownHosts;

	/**
     * Loads dependencies and initializes data structures.
     */
	@Override
	public void init(FloodlightModuleContext context)
			throws FloodlightModuleException 
	{
		log.info(String.format("Initializing %s...", MODULE_NAME));
		Map<String,String> config = context.getConfigParams(this);
        this.table = Byte.parseByte(config.get("table"));
        
		this.floodlightProv = context.getServiceImpl(
				IFloodlightProviderService.class);
        this.linkDiscProv = context.getServiceImpl(ILinkDiscoveryService.class);
        this.deviceProv = context.getServiceImpl(IDeviceService.class);
        
        this.knownHosts = new ConcurrentHashMap<IDevice,Host>();
        
        /*********************************************************************/
        /* TODO: Initialize other class variables, if necessary              */
        
        /*********************************************************************/
	}

	/**
     * Subscribes to events and performs other startup tasks.
     */
	@Override
	public void startUp(FloodlightModuleContext context)
			throws FloodlightModuleException 
	{
		log.info(String.format("Starting %s...", MODULE_NAME));
		this.floodlightProv.addOFSwitchListener(this);
		this.linkDiscProv.addListener(this);
		this.deviceProv.addListener(this);
		
		/*********************************************************************/
		/* TODO: Perform other tasks, if necessary                           */
		
		/*********************************************************************/
	}
	
	/**
	 * Get the table in which this application installs rules.
	 */
	public byte getTable()
	{ return this.table; }
	
    /**
     * Get a list of all known hosts in the network.
     */
    private Collection<Host> getHosts()
    { return this.knownHosts.values(); }
	
    /**
     * Get a map of all active switches in the network. Switch DPID is used as
     * the key.
     */
	private Map<Long, IOFSwitch> getSwitches()
    { return floodlightProv.getAllSwitchMap(); }
	
    /**
     * Get a list of all active links in the network.
     */
    private Collection<Link> getLinks()
    { return linkDiscProv.getLinks().keySet(); }

	/*extra credit: Thomas*/
    private org.openflow.protocol.OFMatch createBroadcastMatch()
	{
		org.openflow.protocol.OFMatch match =
				new org.openflow.protocol.OFMatch();
		ArrayList<org.openflow.protocol.OFMatchField> fields =
				new ArrayList<org.openflow.protocol.OFMatchField>();

		fields.add(new org.openflow.protocol.OFMatchField(
				org.openflow.protocol.OFOXMFieldType.ETH_TYPE,
				net.floodlightcontroller.packet.Ethernet.TYPE_IPv4));
		fields.add(new org.openflow.protocol.OFMatchField(
				org.openflow.protocol.OFOXMFieldType.ETH_DST,
				new byte[] {
						(byte) 0xff, (byte) 0xff, (byte) 0xff,
						(byte) 0xff, (byte) 0xff, (byte) 0xff
				}));

		match.setMatchFields(fields);

		return match;
	}

	private org.openflow.protocol.OFMatch createBroadcastMatch(short inPort)
	{
		org.openflow.protocol.OFMatch match =
				new org.openflow.protocol.OFMatch();
		ArrayList<org.openflow.protocol.OFMatchField> fields =
				new ArrayList<org.openflow.protocol.OFMatchField>();

		fields.add(new org.openflow.protocol.OFMatchField(
				org.openflow.protocol.OFOXMFieldType.ETH_TYPE,
				net.floodlightcontroller.packet.Ethernet.TYPE_IPv4));
		fields.add(new org.openflow.protocol.OFMatchField(
				org.openflow.protocol.OFOXMFieldType.ETH_DST,
				new byte[] {
						(byte) 0xff, (byte) 0xff, (byte) 0xff,
						(byte) 0xff, (byte) 0xff, (byte) 0xff
				}));
		fields.add(new org.openflow.protocol.OFMatchField(
				org.openflow.protocol.OFOXMFieldType.IN_PORT,
				inPort));

		match.setMatchFields(fields);

		return match;
	}

	private void addPort(Map<Long, ArrayList<Short>> ports, long switchId,
			short port)
	{
		if (!ports.containsKey(switchId))
		{
			ports.put(switchId, new ArrayList<Short>());
		}

		if (!ports.get(switchId).contains(port))
		{
			ports.get(switchId).add(port);
		}
	}

	private Map<Long, ArrayList<Short>> getAllBroadcastPorts()
	{
		Map<Long, ArrayList<Short>> ports =
				new HashMap<Long, ArrayList<Short>>();

		for (Long switchId : this.getSwitches().keySet())
		{
			ports.put(switchId, new ArrayList<Short>());
		}

		for (Link link : this.getLinks())
		{
			if (this.getSwitches().containsKey(link.getSrc()))
			{
				this.addPort(ports, link.getSrc(), (short) link.getSrcPort());
			}

			if (this.getSwitches().containsKey(link.getDst()))
			{
				this.addPort(ports, link.getDst(), (short) link.getDstPort());
			}
		}

		for (Host host : this.getHosts())
		{
			if (host != null && host.isAttachedToSwitch()
					&& host.getSwitch() != null)
			{
				this.addPort(ports, host.getSwitch().getId(),
						host.getPort().shortValue());
			}
		}

		return ports;
	}

	private Map<Long, ArrayList<Short>> getSpanningTreePorts()
	{
		Map<Long, ArrayList<Short>> ports =
				new HashMap<Long, ArrayList<Short>>();

		for (Long switchId : this.getSwitches().keySet())
		{
			ports.put(switchId, new ArrayList<Short>());
		}

		if (this.getSwitches().isEmpty())
		{
			return ports;
		}

		Long root = this.getSwitches().keySet().iterator().next();

		java.util.Set<Long> visited = new java.util.HashSet<Long>();
		java.util.Queue<Long> queue = new java.util.LinkedList<Long>();

		visited.add(root);
		queue.add(root);

		while (!queue.isEmpty())
		{
			long current = queue.remove();

			for (Link link : this.getLinks())
			{
				long neighbor = -1;
				short currentPort = -1;
				short neighborPort = -1;

				if (link.getSrc() == current
						&& this.getSwitches().containsKey(link.getDst()))
				{
					neighbor = link.getDst();
					currentPort = (short) link.getSrcPort();
					neighborPort = (short) link.getDstPort();
				}
				else if (link.getDst() == current
						&& this.getSwitches().containsKey(link.getSrc()))
				{
					neighbor = link.getSrc();
					currentPort = (short) link.getDstPort();
					neighborPort = (short) link.getSrcPort();
				}

				if (neighbor != -1 && !visited.contains(neighbor))
				{
					visited.add(neighbor);
					queue.add(neighbor);

					this.addPort(ports, current, currentPort);
					this.addPort(ports, neighbor, neighborPort);
				}
			}
		}

		for (Host host : this.getHosts())
		{
			if (host != null && host.isAttachedToSwitch()
					&& host.getSwitch() != null)
			{
				this.addPort(ports, host.getSwitch().getId(),
						host.getPort().shortValue());
			}
		}

		return ports;
	}

	private void installLoopFreeBroadcastRules()
	{
		Map<Long, ArrayList<Short>> allPorts = this.getAllBroadcastPorts();
		Map<Long, ArrayList<Short>> treePorts = this.getSpanningTreePorts();

		for (IOFSwitch sw : this.getSwitches().values())
		{
			if (sw == null)
			{
				continue;
			}

			edu.brown.cs.sdn.apps.util.SwitchCommands.removeRules(
					sw, this.table, this.createBroadcastMatch());

			if (allPorts.containsKey(sw.getId()))
			{
				for (Short port : allPorts.get(sw.getId()))
				{
					edu.brown.cs.sdn.apps.util.SwitchCommands.removeRules(
							sw, this.table, this.createBroadcastMatch(port));
				}
			}

			if (!treePorts.containsKey(sw.getId()))
			{
				continue;
			}

			ArrayList<Short> ports = treePorts.get(sw.getId());

			if (ports.isEmpty())
			{
				continue;
			}

			ArrayList<org.openflow.protocol.action.OFAction> defaultActions =
					new ArrayList<org.openflow.protocol.action.OFAction>();

			for (Short outputPort : ports)
			{
				org.openflow.protocol.action.OFActionOutput output =
						new org.openflow.protocol.action.OFActionOutput();
				output.setPort(outputPort);
				defaultActions.add(output);
			}

			ArrayList<org.openflow.protocol.instruction.OFInstruction> defaultInstructions =
					new ArrayList<org.openflow.protocol.instruction.OFInstruction>();
			defaultInstructions.add(
					new org.openflow.protocol.instruction.OFInstructionApplyActions(
							defaultActions));

			edu.brown.cs.sdn.apps.util.SwitchCommands.installRule(
					sw,
					this.table,
					(short) (edu.brown.cs.sdn.apps.util.SwitchCommands.DEFAULT_PRIORITY - 1),
					this.createBroadcastMatch(),
					defaultInstructions,
                    (short) 0,
		            (short) 0);

			for (Short inPort : ports)
			{
				ArrayList<org.openflow.protocol.action.OFAction> actions =
						new ArrayList<org.openflow.protocol.action.OFAction>();

				for (Short outputPort : ports)
				{
					if (!outputPort.equals(inPort))
					{
						org.openflow.protocol.action.OFActionOutput output =
								new org.openflow.protocol.action.OFActionOutput();
						output.setPort(outputPort);
						actions.add(output);
					}
				}

				if (actions.isEmpty())
				{
					continue;
				}

				ArrayList<org.openflow.protocol.instruction.OFInstruction> instructions =
						new ArrayList<org.openflow.protocol.instruction.OFInstruction>();
				instructions.add(
						new org.openflow.protocol.instruction.OFInstructionApplyActions(
								actions));

				edu.brown.cs.sdn.apps.util.SwitchCommands.installRule(
						sw,
						this.table,
						edu.brown.cs.sdn.apps.util.SwitchCommands.DEFAULT_PRIORITY,
						this.createBroadcastMatch(inPort),
						instructions,
                        (short) 0,
		                (short) 0);
			}
		}
	}

    /*end of extra credit */

	/*Thomas */
    /**
     * Event handler called when a host joins the network.
     * @param device information about the host
     */
	@Override
	public void deviceAdded(IDevice device) 
	{
		Host host = new Host(device, this.floodlightProv);
		// We only care about a new host if we know its IP
		if (host.getIPv4Address() != null)
		{
			log.info(String.format("Host %s added", host.getName()));
			this.knownHosts.put(device, host);
			
			/*****************************************************************/
			/* TODO: Update routing: add rules to route to new host          */
			for (Host oldHost : this.getHosts())
			{
				if (oldHost == null)
				{
					continue;
				}

				org.openflow.protocol.OFMatch removeMatch =
						new org.openflow.protocol.OFMatch();
				ArrayList<org.openflow.protocol.OFMatchField> removeFields =
						new ArrayList<org.openflow.protocol.OFMatchField>();

				removeFields.add(new org.openflow.protocol.OFMatchField(
						org.openflow.protocol.OFOXMFieldType.ETH_TYPE,
						net.floodlightcontroller.packet.Ethernet.TYPE_IPv4));
				removeFields.add(new org.openflow.protocol.OFMatchField(
						org.openflow.protocol.OFOXMFieldType.ETH_DST,
						net.floodlightcontroller.packet.Ethernet.toByteArray(
								oldHost.getMACAddress())));

				removeMatch.setMatchFields(removeFields);

				for (IOFSwitch sw : this.getSwitches().values())
				{
					edu.brown.cs.sdn.apps.util.SwitchCommands.removeRules(
							sw, this.table, removeMatch);
				}
			}

			for (Host destinationHost : this.getHosts())
			{
				if (destinationHost == null || !destinationHost.isAttachedToSwitch())
				{
					continue;
				}

				IOFSwitch destinationSwitch = destinationHost.getSwitch();

				if (destinationSwitch == null)
				{
					continue;
				}

				Map<IOFSwitch, Integer> distances =
						new HashMap<IOFSwitch, Integer>();
				Map<IOFSwitch, IOFSwitch> parents =
						new HashMap<IOFSwitch, IOFSwitch>();
				Map<IOFSwitch, Boolean> visited =
						new HashMap<IOFSwitch, Boolean>();

				for (IOFSwitch sw : this.getSwitches().values())
				{
					distances.put(sw, Integer.MAX_VALUE);
					parents.put(sw, null);
					visited.put(sw, false);
				}

				distances.put(destinationSwitch, 0);

				while (true)
				{
					IOFSwitch currentSwitch = null;
					int currentDistance = Integer.MAX_VALUE;

					for (IOFSwitch sw : distances.keySet())
					{
						if (!visited.get(sw) && distances.get(sw) < currentDistance)
						{
							currentSwitch = sw;
							currentDistance = distances.get(sw);
						}
					}

					if (currentSwitch == null)
					{
						break;
					}

					visited.put(currentSwitch, true);

					for (Link link : this.getLinks())
					{
						IOFSwitch linkSource = this.getSwitches().get(link.getSrc());
						IOFSwitch linkDestination = this.getSwitches().get(link.getDst());

						if (linkSource == null || linkDestination == null)
						{
							continue;
						}

						if (linkSource.getId() == currentSwitch.getId()
								&& !visited.get(linkDestination))
						{
							int newDistance = distances.get(currentSwitch) + 1;

							if (newDistance < distances.get(linkDestination))
							{
								distances.put(linkDestination, newDistance);
								parents.put(linkDestination, currentSwitch);
							}
						}
					}
				}

				for (IOFSwitch sw : this.getSwitches().values())
				{
					Short outputPort = null;

					if (sw.getId() == destinationSwitch.getId())
					{
						outputPort = destinationHost.getPort().shortValue();
					}
					else
					{
						IOFSwitch nextSwitch = parents.get(sw);

						if (nextSwitch == null)
						{
							continue;
						}

						for (Link link : this.getLinks())
						{
							if (link.getSrc() == sw.getId()
									&& link.getDst() == nextSwitch.getId())
							{
								outputPort = (short) link.getSrcPort();
								break;
							}
						}
					}

					if (outputPort == null)
					{
						continue;
					}

					org.openflow.protocol.OFMatch match =
							new org.openflow.protocol.OFMatch();
					ArrayList<org.openflow.protocol.OFMatchField> fields =
							new ArrayList<org.openflow.protocol.OFMatchField>();

					fields.add(new org.openflow.protocol.OFMatchField(
							org.openflow.protocol.OFOXMFieldType.ETH_TYPE,
							net.floodlightcontroller.packet.Ethernet.TYPE_IPv4));
					fields.add(new org.openflow.protocol.OFMatchField(
							org.openflow.protocol.OFOXMFieldType.ETH_DST,
							net.floodlightcontroller.packet.Ethernet.toByteArray(
									destinationHost.getMACAddress())));

					match.setMatchFields(fields);

					org.openflow.protocol.action.OFActionOutput output =
							new org.openflow.protocol.action.OFActionOutput();
					output.setPort(outputPort);

					ArrayList<org.openflow.protocol.action.OFAction> actions =
							new ArrayList<org.openflow.protocol.action.OFAction>();
					actions.add(output);

					ArrayList<org.openflow.protocol.instruction.OFInstruction> instructions =
							new ArrayList<org.openflow.protocol.instruction.OFInstruction>();
					instructions.add(
							new org.openflow.protocol.instruction.OFInstructionApplyActions(
									actions));

					edu.brown.cs.sdn.apps.util.SwitchCommands.installRule(
							sw,
							this.table,
							edu.brown.cs.sdn.apps.util.SwitchCommands.DEFAULT_PRIORITY,
							match,
							instructions,
                            (short) 0,
		                    (short) 0);
				}
			}
            this.installLoopFreeBroadcastRules();
			/*****************************************************************/
		}
	}

	/*Annabella */
	/**
     * Event handler called when a host is no longer attached to a switch.
     * @param device information about the host
     */
	@Override
	public void deviceRemoved(IDevice device) 
	{
		Host host = this.knownHosts.get(device);
		if (null == host)
		{
			host = new Host(device, this.floodlightProv);
			this.knownHosts.put(device, host);
		}
		
		log.info(String.format("Host %s is no longer attached to a switch", 
				host.getName()));
		
		/*********************************************************************/
		/* TODO: Update routing: remove rules to route to host               */
		org.openflow.protocol.OFMatch removeMatch =
				new org.openflow.protocol.OFMatch();
		ArrayList<org.openflow.protocol.OFMatchField> removeFields =
				new ArrayList<org.openflow.protocol.OFMatchField>();

		removeFields.add(new org.openflow.protocol.OFMatchField(
				org.openflow.protocol.OFOXMFieldType.ETH_TYPE,
				net.floodlightcontroller.packet.Ethernet.TYPE_IPv4));
		removeFields.add(new org.openflow.protocol.OFMatchField(
				org.openflow.protocol.OFOXMFieldType.ETH_DST,
				net.floodlightcontroller.packet.Ethernet.toByteArray(
						host.getMACAddress())));

		removeMatch.setMatchFields(removeFields);

		for (IOFSwitch sw : this.getSwitches().values())
		{
			edu.brown.cs.sdn.apps.util.SwitchCommands.removeRules(
					sw, this.table, removeMatch);
		}

		this.knownHosts.remove(device);

		for (Host oldHost : this.getHosts())
		{
			if (oldHost == null)
			{
				continue;
			}

			org.openflow.protocol.OFMatch oldMatch =
					new org.openflow.protocol.OFMatch();
			ArrayList<org.openflow.protocol.OFMatchField> oldFields =
					new ArrayList<org.openflow.protocol.OFMatchField>();

			oldFields.add(new org.openflow.protocol.OFMatchField(
					org.openflow.protocol.OFOXMFieldType.ETH_TYPE,
					net.floodlightcontroller.packet.Ethernet.TYPE_IPv4));
			oldFields.add(new org.openflow.protocol.OFMatchField(
					org.openflow.protocol.OFOXMFieldType.ETH_DST,
					net.floodlightcontroller.packet.Ethernet.toByteArray(
							oldHost.getMACAddress())));

			oldMatch.setMatchFields(oldFields);

			for (IOFSwitch sw : this.getSwitches().values())
			{
				edu.brown.cs.sdn.apps.util.SwitchCommands.removeRules(
						sw, this.table, oldMatch);
			}
		}

		for (Host destinationHost : this.getHosts())
		{
			if (destinationHost == null || !destinationHost.isAttachedToSwitch())
			{
				continue;
			}

			IOFSwitch destinationSwitch = destinationHost.getSwitch();

			if (destinationSwitch == null)
			{
				continue;
			}

			Map<IOFSwitch, Integer> distances =
					new HashMap<IOFSwitch, Integer>();
			Map<IOFSwitch, IOFSwitch> parents =
					new HashMap<IOFSwitch, IOFSwitch>();
			Map<IOFSwitch, Boolean> visited =
					new HashMap<IOFSwitch, Boolean>();

			for (IOFSwitch sw : this.getSwitches().values())
			{
				distances.put(sw, Integer.MAX_VALUE);
				parents.put(sw, null);
				visited.put(sw, false);
			}

			distances.put(destinationSwitch, 0);

			while (true)
			{
				IOFSwitch currentSwitch = null;
				int currentDistance = Integer.MAX_VALUE;

				for (IOFSwitch sw : distances.keySet())
				{
					if (!visited.get(sw) && distances.get(sw) < currentDistance)
					{
						currentSwitch = sw;
						currentDistance = distances.get(sw);
					}
				}

				if (currentSwitch == null)
				{
					break;
				}

				visited.put(currentSwitch, true);

				for (Link link : this.getLinks())
				{
					IOFSwitch linkSource = this.getSwitches().get(link.getSrc());
					IOFSwitch linkDestination = this.getSwitches().get(link.getDst());

					if (linkSource == null || linkDestination == null)
					{
						continue;
					}

					if (linkSource.getId() == currentSwitch.getId()
							&& !visited.get(linkDestination))
					{
						int newDistance = distances.get(currentSwitch) + 1;

						if (newDistance < distances.get(linkDestination))
						{
							distances.put(linkDestination, newDistance);
							parents.put(linkDestination, currentSwitch);
						}
					}
				}
			}

			for (IOFSwitch sw : this.getSwitches().values())
			{
				Short outputPort = null;

				if (sw.getId() == destinationSwitch.getId())
				{
					outputPort = destinationHost.getPort().shortValue();
				}
				else
				{
					IOFSwitch nextSwitch = parents.get(sw);

					if (nextSwitch == null)
					{
						continue;
					}

					for (Link link : this.getLinks())
					{
						if (link.getSrc() == sw.getId()
								&& link.getDst() == nextSwitch.getId())
						{
							outputPort = (short) link.getSrcPort();
							break;
						}
					}
				}

				if (outputPort == null)
				{
					continue;
				}

				org.openflow.protocol.OFMatch match =
						new org.openflow.protocol.OFMatch();
				ArrayList<org.openflow.protocol.OFMatchField> fields =
						new ArrayList<org.openflow.protocol.OFMatchField>();

				fields.add(new org.openflow.protocol.OFMatchField(
						org.openflow.protocol.OFOXMFieldType.ETH_TYPE,
						net.floodlightcontroller.packet.Ethernet.TYPE_IPv4));
				fields.add(new org.openflow.protocol.OFMatchField(
						org.openflow.protocol.OFOXMFieldType.ETH_DST,
						net.floodlightcontroller.packet.Ethernet.toByteArray(
								destinationHost.getMACAddress())));

				match.setMatchFields(fields);

				org.openflow.protocol.action.OFActionOutput output =
						new org.openflow.protocol.action.OFActionOutput();
				output.setPort(outputPort);

				ArrayList<org.openflow.protocol.action.OFAction> actions =
						new ArrayList<org.openflow.protocol.action.OFAction>();
				actions.add(output);

				ArrayList<org.openflow.protocol.instruction.OFInstruction> instructions =
						new ArrayList<org.openflow.protocol.instruction.OFInstruction>();
				instructions.add(
						new org.openflow.protocol.instruction.OFInstructionApplyActions(
								actions));

				edu.brown.cs.sdn.apps.util.SwitchCommands.installRule(
						sw,
						this.table,
						edu.brown.cs.sdn.apps.util.SwitchCommands.DEFAULT_PRIORITY,
						match,
						instructions,
                        (short) 0,
		                (short) 0);
			}
		}
        this.installLoopFreeBroadcastRules();
		/*********************************************************************/
	}

	/*Annabella */
	/**
     * Event handler called when a host moves within the network.
     * @param device information about the host
     */
	@Override
	public void deviceMoved(IDevice device) 
	{
		Host host = this.knownHosts.get(device);
		if (null == host)
		{
			host = new Host(device, this.floodlightProv);
			this.knownHosts.put(device, host);
		}
		
		if (!host.isAttachedToSwitch())
		{
			this.deviceRemoved(device);
			return;
		}
		log.info(String.format("Host %s moved to s%d:%d", host.getName(),
				host.getSwitch().getId(), host.getPort()));
		
		/*********************************************************************/
		/* TODO: Update routing: change rules to route to host               */
		for (Host oldHost : this.getHosts())
		{
			if (oldHost == null)
			{
				continue;
			}

			org.openflow.protocol.OFMatch removeMatch =
					new org.openflow.protocol.OFMatch();
			ArrayList<org.openflow.protocol.OFMatchField> removeFields =
					new ArrayList<org.openflow.protocol.OFMatchField>();

			removeFields.add(new org.openflow.protocol.OFMatchField(
					org.openflow.protocol.OFOXMFieldType.ETH_TYPE,
					net.floodlightcontroller.packet.Ethernet.TYPE_IPv4));
			removeFields.add(new org.openflow.protocol.OFMatchField(
					org.openflow.protocol.OFOXMFieldType.ETH_DST,
					net.floodlightcontroller.packet.Ethernet.toByteArray(
							oldHost.getMACAddress())));

			removeMatch.setMatchFields(removeFields);

			for (IOFSwitch sw : this.getSwitches().values())
			{
				edu.brown.cs.sdn.apps.util.SwitchCommands.removeRules(
						sw, this.table, removeMatch);
			}
		}

		for (Host destinationHost : this.getHosts())
		{
			if (destinationHost == null || !destinationHost.isAttachedToSwitch())
			{
				continue;
			}

			IOFSwitch destinationSwitch = destinationHost.getSwitch();

			if (destinationSwitch == null)
			{
				continue;
			}

			Map<IOFSwitch, Integer> distances =
					new HashMap<IOFSwitch, Integer>();
			Map<IOFSwitch, IOFSwitch> parents =
					new HashMap<IOFSwitch, IOFSwitch>();
			Map<IOFSwitch, Boolean> visited =
					new HashMap<IOFSwitch, Boolean>();

			for (IOFSwitch sw : this.getSwitches().values())
			{
				distances.put(sw, Integer.MAX_VALUE);
				parents.put(sw, null);
				visited.put(sw, false);
			}

			distances.put(destinationSwitch, 0);

			while (true)
			{
				IOFSwitch currentSwitch = null;
				int currentDistance = Integer.MAX_VALUE;

				for (IOFSwitch sw : distances.keySet())
				{
					if (!visited.get(sw) && distances.get(sw) < currentDistance)
					{
						currentSwitch = sw;
						currentDistance = distances.get(sw);
					}
				}

				if (currentSwitch == null)
				{
					break;
				}

				visited.put(currentSwitch, true);

				for (Link link : this.getLinks())
				{
					IOFSwitch linkSource = this.getSwitches().get(link.getSrc());
					IOFSwitch linkDestination = this.getSwitches().get(link.getDst());

					if (linkSource == null || linkDestination == null)
					{
						continue;
					}

					if (linkSource.getId() == currentSwitch.getId()
							&& !visited.get(linkDestination))
					{
						int newDistance = distances.get(currentSwitch) + 1;

						if (newDistance < distances.get(linkDestination))
						{
							distances.put(linkDestination, newDistance);
							parents.put(linkDestination, currentSwitch);
						}
					}
				}
			}

			for (IOFSwitch sw : this.getSwitches().values())
			{
				Short outputPort = null;

				if (sw.getId() == destinationSwitch.getId())
				{
					outputPort = destinationHost.getPort().shortValue();
				}
				else
				{
					IOFSwitch nextSwitch = parents.get(sw);

					if (nextSwitch == null)
					{
						continue;
					}

					for (Link link : this.getLinks())
					{
						if (link.getSrc() == sw.getId()
								&& link.getDst() == nextSwitch.getId())
						{
							outputPort = (short) link.getSrcPort();
							break;
						}
					}
				}

				if (outputPort == null)
				{
					continue;
				}

				org.openflow.protocol.OFMatch match =
						new org.openflow.protocol.OFMatch();
				ArrayList<org.openflow.protocol.OFMatchField> fields =
						new ArrayList<org.openflow.protocol.OFMatchField>();

				fields.add(new org.openflow.protocol.OFMatchField(
						org.openflow.protocol.OFOXMFieldType.ETH_TYPE,
						net.floodlightcontroller.packet.Ethernet.TYPE_IPv4));
				fields.add(new org.openflow.protocol.OFMatchField(
						org.openflow.protocol.OFOXMFieldType.ETH_DST,
						net.floodlightcontroller.packet.Ethernet.toByteArray(
								destinationHost.getMACAddress())));

				match.setMatchFields(fields);

				org.openflow.protocol.action.OFActionOutput output =
						new org.openflow.protocol.action.OFActionOutput();
				output.setPort(outputPort);

				ArrayList<org.openflow.protocol.action.OFAction> actions =
						new ArrayList<org.openflow.protocol.action.OFAction>();
				actions.add(output);

				ArrayList<org.openflow.protocol.instruction.OFInstruction> instructions =
						new ArrayList<org.openflow.protocol.instruction.OFInstruction>();
				instructions.add(
						new org.openflow.protocol.instruction.OFInstructionApplyActions(
								actions));

				edu.brown.cs.sdn.apps.util.SwitchCommands.installRule(
						sw,
						this.table,
						edu.brown.cs.sdn.apps.util.SwitchCommands.DEFAULT_PRIORITY,
						match,
						instructions,
                        (short) 0,
		                (short) 0);
			}
		}
        this.installLoopFreeBroadcastRules();
		/*********************************************************************/
	}
	
	/*Annabella */
    /**
     * Event handler called when a switch joins the network.
     * @param DPID for the switch
     */
	@Override		
	public void switchAdded(long switchId) 
	{
		IOFSwitch sw = this.floodlightProv.getSwitch(switchId);
		log.info(String.format("Switch s%d added", switchId));
		
		/*********************************************************************/
		/* TODO: Update routing: change routing rules for all hosts          */
		for (Host oldHost : this.getHosts())
		{
			if (oldHost == null)
			{
				continue;
			}

			org.openflow.protocol.OFMatch removeMatch =
					new org.openflow.protocol.OFMatch();
			ArrayList<org.openflow.protocol.OFMatchField> removeFields =
					new ArrayList<org.openflow.protocol.OFMatchField>();

			removeFields.add(new org.openflow.protocol.OFMatchField(
					org.openflow.protocol.OFOXMFieldType.ETH_TYPE,
					net.floodlightcontroller.packet.Ethernet.TYPE_IPv4));
			removeFields.add(new org.openflow.protocol.OFMatchField(
					org.openflow.protocol.OFOXMFieldType.ETH_DST,
					net.floodlightcontroller.packet.Ethernet.toByteArray(
							oldHost.getMACAddress())));

			removeMatch.setMatchFields(removeFields);

			for (IOFSwitch sw2 : this.getSwitches().values())
			{
				edu.brown.cs.sdn.apps.util.SwitchCommands.removeRules(
						sw2, this.table, removeMatch);
			}
		}

		for (Host destinationHost : this.getHosts())
		{
			if (destinationHost == null || !destinationHost.isAttachedToSwitch())
			{
				continue;
			}

			IOFSwitch destinationSwitch = destinationHost.getSwitch();

			if (destinationSwitch == null)
			{
				continue;
			}

			Map<IOFSwitch, Integer> distances =
					new HashMap<IOFSwitch, Integer>();
			Map<IOFSwitch, IOFSwitch> parents =
					new HashMap<IOFSwitch, IOFSwitch>();
			Map<IOFSwitch, Boolean> visited =
					new HashMap<IOFSwitch, Boolean>();

			for (IOFSwitch sw2 : this.getSwitches().values())
			{
				distances.put(sw2, Integer.MAX_VALUE);
				parents.put(sw2, null);
				visited.put(sw2, false);
			}

			distances.put(destinationSwitch, 0);

			while (true)
			{
				IOFSwitch currentSwitch = null;
				int currentDistance = Integer.MAX_VALUE;

				for (IOFSwitch sw2 : distances.keySet())
				{
					if (!visited.get(sw2) && distances.get(sw2) < currentDistance)
					{
						currentSwitch = sw2;
						currentDistance = distances.get(sw2);
					}
				}

				if (currentSwitch == null)
				{
					break;
				}

				visited.put(currentSwitch, true);

				for (Link link : this.getLinks())
				{
					IOFSwitch linkSource = this.getSwitches().get(link.getSrc());
					IOFSwitch linkDestination = this.getSwitches().get(link.getDst());

					if (linkSource == null || linkDestination == null)
					{
						continue;
					}

					if (linkSource.getId() == currentSwitch.getId()
							&& !visited.get(linkDestination))
					{
						int newDistance = distances.get(currentSwitch) + 1;

						if (newDistance < distances.get(linkDestination))
						{
							distances.put(linkDestination, newDistance);
							parents.put(linkDestination, currentSwitch);
						}
					}
				}
			}

			for (IOFSwitch sw2 : this.getSwitches().values())
			{
				Short outputPort = null;

				if (sw2.getId() == destinationSwitch.getId())
				{
					outputPort = destinationHost.getPort().shortValue();
				}
				else
				{
					IOFSwitch nextSwitch = parents.get(sw2);

					if (nextSwitch == null)
					{
						continue;
					}

					for (Link link : this.getLinks())
					{
						if (link.getSrc() == sw2.getId()
								&& link.getDst() == nextSwitch.getId())
						{
							outputPort = (short) link.getSrcPort();
							break;
						}
					}
				}

				if (outputPort == null)
				{
					continue;
				}

				org.openflow.protocol.OFMatch match =
						new org.openflow.protocol.OFMatch();
				ArrayList<org.openflow.protocol.OFMatchField> fields =
						new ArrayList<org.openflow.protocol.OFMatchField>();

				fields.add(new org.openflow.protocol.OFMatchField(
						org.openflow.protocol.OFOXMFieldType.ETH_TYPE,
						net.floodlightcontroller.packet.Ethernet.TYPE_IPv4));
				fields.add(new org.openflow.protocol.OFMatchField(
						org.openflow.protocol.OFOXMFieldType.ETH_DST,
						net.floodlightcontroller.packet.Ethernet.toByteArray(
								destinationHost.getMACAddress())));

				match.setMatchFields(fields);

				org.openflow.protocol.action.OFActionOutput output =
						new org.openflow.protocol.action.OFActionOutput();
				output.setPort(outputPort);

				ArrayList<org.openflow.protocol.action.OFAction> actions =
						new ArrayList<org.openflow.protocol.action.OFAction>();
				actions.add(output);

				ArrayList<org.openflow.protocol.instruction.OFInstruction> instructions =
						new ArrayList<org.openflow.protocol.instruction.OFInstruction>();
				instructions.add(
						new org.openflow.protocol.instruction.OFInstructionApplyActions(
								actions));

				edu.brown.cs.sdn.apps.util.SwitchCommands.installRule(
						sw2,
						this.table,
						edu.brown.cs.sdn.apps.util.SwitchCommands.DEFAULT_PRIORITY,
						match,
						instructions,
                        (short) 0,
		                (short) 0);
			}
		}
        this.installLoopFreeBroadcastRules();
		/*********************************************************************/
	}

	/*D'angelo */
	/**
	 * Event handler called when a switch leaves the network.
	 * @param DPID for the switch
	 */
	@Override
	public void switchRemoved(long switchId) 
	{
		IOFSwitch sw = this.floodlightProv.getSwitch(switchId);
		log.info(String.format("Switch s%d removed", switchId));
		
		/*********************************************************************/
		/* TODO: Update routing: change routing rules for all hosts          */
		for (Host oldHost : this.getHosts())
		{
			if (oldHost == null)
			{
				continue;
			}

			org.openflow.protocol.OFMatch removeMatch =
					new org.openflow.protocol.OFMatch();
			ArrayList<org.openflow.protocol.OFMatchField> removeFields =
					new ArrayList<org.openflow.protocol.OFMatchField>();

			removeFields.add(new org.openflow.protocol.OFMatchField(
					org.openflow.protocol.OFOXMFieldType.ETH_TYPE,
					net.floodlightcontroller.packet.Ethernet.TYPE_IPv4));
			removeFields.add(new org.openflow.protocol.OFMatchField(
					org.openflow.protocol.OFOXMFieldType.ETH_DST,
					net.floodlightcontroller.packet.Ethernet.toByteArray(
							oldHost.getMACAddress())));

			removeMatch.setMatchFields(removeFields);

			for (IOFSwitch sw2 : this.getSwitches().values())
			{
				edu.brown.cs.sdn.apps.util.SwitchCommands.removeRules(
						sw2, this.table, removeMatch);
			}
		}

		for (Host destinationHost : this.getHosts())
		{
			if (destinationHost == null || !destinationHost.isAttachedToSwitch())
			{
				continue;
			}

			IOFSwitch destinationSwitch = destinationHost.getSwitch();

			if (destinationSwitch == null)
			{
				continue;
			}

			Map<IOFSwitch, Integer> distances =
					new HashMap<IOFSwitch, Integer>();
			Map<IOFSwitch, IOFSwitch> parents =
					new HashMap<IOFSwitch, IOFSwitch>();
			Map<IOFSwitch, Boolean> visited =
					new HashMap<IOFSwitch, Boolean>();

			for (IOFSwitch sw2 : this.getSwitches().values())
			{
				distances.put(sw2, Integer.MAX_VALUE);
				parents.put(sw2, null);
				visited.put(sw2, false);
			}

			distances.put(destinationSwitch, 0);

			while (true)
			{
				IOFSwitch currentSwitch = null;
				int currentDistance = Integer.MAX_VALUE;

				for (IOFSwitch sw2 : distances.keySet())
				{
					if (!visited.get(sw2) && distances.get(sw2) < currentDistance)
					{
						currentSwitch = sw2;
						currentDistance = distances.get(sw2);
					}
				}

				if (currentSwitch == null)
				{
					break;
				}

				visited.put(currentSwitch, true);

				for (Link link : this.getLinks())
				{
					IOFSwitch linkSource = this.getSwitches().get(link.getSrc());
					IOFSwitch linkDestination = this.getSwitches().get(link.getDst());

					if (linkSource == null || linkDestination == null)
					{
						continue;
					}

					if (linkSource.getId() == currentSwitch.getId()
							&& !visited.get(linkDestination))
					{
						int newDistance = distances.get(currentSwitch) + 1;

						if (newDistance < distances.get(linkDestination))
						{
							distances.put(linkDestination, newDistance);
							parents.put(linkDestination, currentSwitch);
						}
					}
				}
			}

			for (IOFSwitch sw2 : this.getSwitches().values())
			{
				Short outputPort = null;

				if (sw2.getId() == destinationSwitch.getId())
				{
					outputPort = destinationHost.getPort().shortValue();
				}
				else
				{
					IOFSwitch nextSwitch = parents.get(sw2);

					if (nextSwitch == null)
					{
						continue;
					}

					for (Link link : this.getLinks())
					{
						if (link.getSrc() == sw2.getId()
								&& link.getDst() == nextSwitch.getId())
						{
							outputPort = (short) link.getSrcPort();
							break;
						}
					}
				}

				if (outputPort == null)
				{
					continue;
				}

				org.openflow.protocol.OFMatch match =
						new org.openflow.protocol.OFMatch();
				ArrayList<org.openflow.protocol.OFMatchField> fields =
						new ArrayList<org.openflow.protocol.OFMatchField>();

				fields.add(new org.openflow.protocol.OFMatchField(
						org.openflow.protocol.OFOXMFieldType.ETH_TYPE,
						net.floodlightcontroller.packet.Ethernet.TYPE_IPv4));
				fields.add(new org.openflow.protocol.OFMatchField(
						org.openflow.protocol.OFOXMFieldType.ETH_DST,
						net.floodlightcontroller.packet.Ethernet.toByteArray(
								destinationHost.getMACAddress())));

				match.setMatchFields(fields);

				org.openflow.protocol.action.OFActionOutput output =
						new org.openflow.protocol.action.OFActionOutput();
				output.setPort(outputPort);

				ArrayList<org.openflow.protocol.action.OFAction> actions =
						new ArrayList<org.openflow.protocol.action.OFAction>();
				actions.add(output);

				ArrayList<org.openflow.protocol.instruction.OFInstruction> instructions =
						new ArrayList<org.openflow.protocol.instruction.OFInstruction>();
				instructions.add(
						new org.openflow.protocol.instruction.OFInstructionApplyActions(
								actions));

				edu.brown.cs.sdn.apps.util.SwitchCommands.installRule(
						sw2,
						this.table,
						edu.brown.cs.sdn.apps.util.SwitchCommands.DEFAULT_PRIORITY,
						match,
						instructions,
                        (short) 0,
		                (short) 0);
			}
		}
        this.installLoopFreeBroadcastRules();
		/*********************************************************************/
	}

	/*D’angelo */
	/**
	 * Event handler called when multiple links go up or down.
	 * @param updateList information about the change in each link's state
	 */
	@Override
	public void linkDiscoveryUpdate(List<LDUpdate> updateList) 
	{
		for (LDUpdate update : updateList)
		{
			// If we only know the switch & port for one end of the link, then
			// the link must be from a switch to a host
			if (0 == update.getDst())
			{
				log.info(String.format("Link s%s:%d -> host updated", 
					update.getSrc(), update.getSrcPort()));
			}
			// Otherwise, the link is between two switches
			else
			{
				log.info(String.format("Link s%s:%d -> %s:%d updated", 
					update.getSrc(), update.getSrcPort(),
					update.getDst(), update.getDstPort()));
			}
		}
		
		/*********************************************************************/
		/* TODO: Update routing: change routing rules for all hosts          */
		for (Host oldHost : this.getHosts())
		{
			if (oldHost == null)
			{
				continue;
			}

			org.openflow.protocol.OFMatch removeMatch =
					new org.openflow.protocol.OFMatch();
			ArrayList<org.openflow.protocol.OFMatchField> removeFields =
					new ArrayList<org.openflow.protocol.OFMatchField>();

			removeFields.add(new org.openflow.protocol.OFMatchField(
					org.openflow.protocol.OFOXMFieldType.ETH_TYPE,
					net.floodlightcontroller.packet.Ethernet.TYPE_IPv4));
			removeFields.add(new org.openflow.protocol.OFMatchField(
					org.openflow.protocol.OFOXMFieldType.ETH_DST,
					net.floodlightcontroller.packet.Ethernet.toByteArray(
							oldHost.getMACAddress())));

			removeMatch.setMatchFields(removeFields);

			for (IOFSwitch sw : this.getSwitches().values())
			{
				edu.brown.cs.sdn.apps.util.SwitchCommands.removeRules(
						sw, this.table, removeMatch);
			}
		}

		for (Host destinationHost : this.getHosts())
		{
			if (destinationHost == null || !destinationHost.isAttachedToSwitch())
			{
				continue;
			}

			IOFSwitch destinationSwitch = destinationHost.getSwitch();

			if (destinationSwitch == null)
			{
				continue;
			}

			Map<IOFSwitch, Integer> distances =
					new HashMap<IOFSwitch, Integer>();
			Map<IOFSwitch, IOFSwitch> parents =
					new HashMap<IOFSwitch, IOFSwitch>();
			Map<IOFSwitch, Boolean> visited =
					new HashMap<IOFSwitch, Boolean>();

			for (IOFSwitch sw : this.getSwitches().values())
			{
				distances.put(sw, Integer.MAX_VALUE);
				parents.put(sw, null);
				visited.put(sw, false);
			}

			distances.put(destinationSwitch, 0);

			while (true)
			{
				IOFSwitch currentSwitch = null;
				int currentDistance = Integer.MAX_VALUE;

				for (IOFSwitch sw : distances.keySet())
				{
					if (!visited.get(sw) && distances.get(sw) < currentDistance)
					{
						currentSwitch = sw;
						currentDistance = distances.get(sw);
					}
				}

				if (currentSwitch == null)
				{
					break;
				}

				visited.put(currentSwitch, true);

				for (Link link : this.getLinks())
				{
					IOFSwitch linkSource = this.getSwitches().get(link.getSrc());
					IOFSwitch linkDestination = this.getSwitches().get(link.getDst());

					if (linkSource == null || linkDestination == null)
					{
						continue;
					}

					if (linkSource.getId() == currentSwitch.getId()
							&& !visited.get(linkDestination))
					{
						int newDistance = distances.get(currentSwitch) + 1;

						if (newDistance < distances.get(linkDestination))
						{
							distances.put(linkDestination, newDistance);
							parents.put(linkDestination, currentSwitch);
						}
					}
				}
			}

			for (IOFSwitch sw : this.getSwitches().values())
			{
				Short outputPort = null;

				if (sw.getId() == destinationSwitch.getId())
				{
					outputPort = destinationHost.getPort().shortValue();
				}
				else
				{
					IOFSwitch nextSwitch = parents.get(sw);

					if (nextSwitch == null)
					{
						continue;
					}

					for (Link link : this.getLinks())
					{
						if (link.getSrc() == sw.getId()
								&& link.getDst() == nextSwitch.getId())
						{
							outputPort = (short) link.getSrcPort();
							break;
						}
					}
				}

				if (outputPort == null)
				{
					continue;
				}

				org.openflow.protocol.OFMatch match =
						new org.openflow.protocol.OFMatch();
				ArrayList<org.openflow.protocol.OFMatchField> fields =
						new ArrayList<org.openflow.protocol.OFMatchField>();

				fields.add(new org.openflow.protocol.OFMatchField(
						org.openflow.protocol.OFOXMFieldType.ETH_TYPE,
						net.floodlightcontroller.packet.Ethernet.TYPE_IPv4));
				fields.add(new org.openflow.protocol.OFMatchField(
						org.openflow.protocol.OFOXMFieldType.ETH_DST,
						net.floodlightcontroller.packet.Ethernet.toByteArray(
								destinationHost.getMACAddress())));

				match.setMatchFields(fields);

				org.openflow.protocol.action.OFActionOutput output =
						new org.openflow.protocol.action.OFActionOutput();
				output.setPort(outputPort);

				ArrayList<org.openflow.protocol.action.OFAction> actions =
						new ArrayList<org.openflow.protocol.action.OFAction>();
				actions.add(output);

				ArrayList<org.openflow.protocol.instruction.OFInstruction> instructions =
						new ArrayList<org.openflow.protocol.instruction.OFInstruction>();
				instructions.add(
						new org.openflow.protocol.instruction.OFInstructionApplyActions(
								actions));

				edu.brown.cs.sdn.apps.util.SwitchCommands.installRule(
						sw,
						this.table,
						edu.brown.cs.sdn.apps.util.SwitchCommands.DEFAULT_PRIORITY,
						match,
						instructions,
                        (short) 0,
		                (short) 0);
			}
		}
        this.installLoopFreeBroadcastRules();
		/*********************************************************************/
	}

	/**
	 * Event handler called when link goes up or down.
	 * @param update information about the change in link state
	 */
	@Override
	public void linkDiscoveryUpdate(LDUpdate update) 
	{ this.linkDiscoveryUpdate(Arrays.asList(update)); }
	
	/**
     * Event handler called when the IP address of a host changes.
     * @param device information about the host
     */
	@Override
	public void deviceIPV4AddrChanged(IDevice device) 
	{ this.deviceAdded(device); }

	/**
     * Event handler called when the VLAN of a host changes.
     * @param device information about the host
     */
	@Override
	public void deviceVlanChanged(IDevice device) 
	{ /* Nothing we need to do, since we're not using VLANs */ }
	
	/**
	 * Event handler called when the controller becomes the master for a switch.
	 * @param DPID for the switch
	 */
	@Override
	public void switchActivated(long switchId) 
	{ /* Nothing we need to do, since we're not switching controller roles */ }

	/**
	 * Event handler called when some attribute of a switch changes.
	 * @param DPID for the switch
	 */
	@Override
	public void switchChanged(long switchId) 
	{ /* Nothing we need to do */ }
	
	/**
	 * Event handler called when a port on a switch goes up or down, or is
	 * added or removed.
	 * @param DPID for the switch
	 * @param port the port on the switch whose status changed
	 * @param type the type of status change (up, down, add, remove)
	 */
	@Override
	public void switchPortChanged(long switchId, ImmutablePort port,
			PortChangeType type) 
	{ /* Nothing we need to do, since we'll get a linkDiscoveryUpdate event */ }

	/**
	 * Gets a name for this module.
	 * @return name for this module
	 */
	@Override
	public String getName() 
	{ return this.MODULE_NAME; }

	/**
	 * Check if events must be passed to another module before this module is
	 * notified of the event.
	 */
	@Override
	public boolean isCallbackOrderingPrereq(String type, String name) 
	{ return false; }

	/**
	 * Check if events must be passed to another module after this module has
	 * been notified of the event.
	 */
	@Override
	public boolean isCallbackOrderingPostreq(String type, String name) 
	{ return false; }
	
    /**
     * Tell the module system which services we provide.
     */
	@Override
	public Collection<Class<? extends IFloodlightService>> getModuleServices() 
	{
		Collection<Class<? extends IFloodlightService>> services =
					new ArrayList<Class<? extends IFloodlightService>>();
		services.add(InterfaceShortestPathSwitching.class);
		return services; 
	}

	/**
     * Tell the module system which services we implement.
     */
	@Override
	public Map<Class<? extends IFloodlightService>, IFloodlightService> 
			getServiceImpls() 
	{ 
        Map<Class<? extends IFloodlightService>, IFloodlightService> services =
        			new HashMap<Class<? extends IFloodlightService>, 
        					IFloodlightService>();
        // We are the class that implements the service
        services.put(InterfaceShortestPathSwitching.class, this);
        return services;
	}

	/**
     * Tell the module system which modules we depend on.
     */
	@Override
	public Collection<Class<? extends IFloodlightService>> 
			getModuleDependencies() 
	{
		Collection<Class<? extends IFloodlightService >> modules =
	            new ArrayList<Class<? extends IFloodlightService>>();
		modules.add(IFloodlightProviderService.class);
		modules.add(ILinkDiscoveryService.class);
		modules.add(IDeviceService.class);
        return modules;
	}
}