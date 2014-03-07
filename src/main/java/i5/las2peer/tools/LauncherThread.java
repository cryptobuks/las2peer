package i5.las2peer.tools;

import i5.las2peer.p2p.NodeException;

import java.io.File;
import java.io.IOException;

/**
 * A simple thread handling one launcher task for running multiple launchers of
 * a configuration directory via {@link ExtendedTestLauncher#launchFromConfigDir}. 
 * 
 * @author Holger Jan&szlig;en
 *
 */
public class LauncherThread extends Thread {
	
	private ExtendedTestLauncher launcher = null;
	
	private File config;
	private int nodeCounter;

	private File logDir;
	
	
	private boolean fail = false;
	
	
	
	/**
	 * create a new Launcher Thread starting a single node
	 * defined by a single config file
	 * 
	 * 
	 * @param file
	 * @param nodeCounter
	 * @param logDir
	 */
	public LauncherThread ( File file, int nodeCounter, File logDir ) {
		this.nodeCounter = nodeCounter;
		config = file;
		this.logDir = logDir;
	}
	
	/**
	 * the actual worker method
	 */
	public void run () {
		try {
			String content = FileContentReader.read( config );
			String[] args = content.split ("\r?\n");
			
			ColoredOutput.printlnYellow("configuring node " + nodeCounter + " from file " + config);

			launcher = ExtendedTestLauncher.launchSingle(args, nodeCounter, logDir, null); //TODO Classloader
			
			// wait until launcher (node) is finished
			while ( ! launcher.isFinished () ) {
				try {
					Thread.sleep( 1000 );
				} catch (InterruptedException e) {
				}
			}			
		} catch (IOException e) {
			ColoredOutput.printlnRed ( "Error reading contents of file " + config + " - " + e  );
			fail = true;
		} catch (NodeException e) {
			ColoredOutput.printlnRed("Error launching node " + nodeCounter + ": " + e);
			fail = true;
		}
	}
	
	/**
	 * getter for the created launcher
	 * 
	 * @return	the assigned launcher
	 */
	public ExtendedTestLauncher getLauncher () {
		return launcher;
	}
	
	
	/**
	 * is the assigned launcher finished?
	 * i.e. has shutdown been called or an error occurred?  
	 * 
	 * @return	true, if the corresponding launcher is finished 
	 */
	public boolean isFinished () {
		if( fail )
			return true;
		if ( launcher == null )
			return false;
		else
			return launcher.isFinished();
	}
	
}