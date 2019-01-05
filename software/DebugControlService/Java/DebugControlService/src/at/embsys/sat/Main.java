/*
 * Author: Jürgen Hausladen
 * Copyright (c) 2016. SAT, FH Technikum Wien
 * License: AGPL <http://www.gnu.org/licenses/agpl.txt>
 */

package at.embsys.sat;

import at.embsys.sat.jlink.OnChipDebugSystemSoftwareJLink;
import at.embsys.sat.jlink.RedirectToJLink;
import at.embsys.sat.oocd.OnChipDebugSystemSoftwareOpenOCD;
import at.embsys.sat.oocd.RedirectToOOCD;
import at.embsys.sat.websocket.WebSocketConnectionHandler;
import at.embsys.sat.websocket.WebSocketServer;
import ch.qos.logback.classic.Level;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.beans.binding.Bindings;
import javafx.beans.value.ChangeListener;
import javafx.beans.value.ObservableValue;
import javafx.event.EventHandler;
import javafx.fxml.FXMLLoader;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.control.*;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.layout.VBox;
import javafx.scene.shape.Circle;
import javafx.stage.Stage;
import javafx.stage.WindowEvent;
import org.slf4j.LoggerFactory;

import javax.usb.UsbHostManager;
import javax.usb.UsbServices;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Optional;


// -Dglass.platform=Monocle -Dmonocle.platform=Headless -Dprism.order=sw

public class Main extends Application {

	private WebSocketServer websocketServer;
	private OnChipDebugSystemSoftwareJLink ocdssJlink;
	private OnChipDebugSystemSoftwareOpenOCD ocdssOOCD;
	private PlatformDetector platformDetector;
	private RemoteGDBConnector remotegdbconnector;
	private double prefHeight = 400;
	//private static String hwPlatform, serialNumber;
	private static int wsPort;
	private static int jlinkPort;
	private static int oocdPort;
	private static int startJlink = 0;
	private static int startOOCD = 0;

	/* Required for the flash download feature of the IDE. The downloaded program is ran once a
	 * network connection is established. Otherwise the GDB server halts the program every time
	 * the hardware is successfully recognized, e.g., if the GDB server is restarted. */
	private static String jLinkGDBServerInitConfig = "go";

	private static boolean jLinkInitConfigAvailable = true;
	private static String keyStoreFilePath = "";
	private static String targetPlatform = "";
	public static String target = "Infineon";
	public static ImageView imageView;
	public static VBox enumUsbVbox;
	public static ListView<String> platFormListView;

	public static final List<String> deviceInfo = new ArrayList<String>();
	public static Level logLevel = Level.DEBUG;
	public static String logLevelJetty = "INFO";
	public static HashMap<ManufactProductPair,DriverType> driverTypes;

	private final String OS = System.getProperty("os.name").toLowerCase();
	private final Alert alert = new Alert(Alert.AlertType.INFORMATION);

	private static ch.qos.logback.classic.Logger logger;

	@Override
	public void start(final Stage primaryStage) throws Exception {

		/* Create UI */
		Parent root = FXMLLoader.load(getClass().getResource("debugcontrolservice.fxml"));
		root.setStyle("-fx-background-color: transparent;");
		primaryStage.getIcons().add(new Image(this.getClass().getResourceAsStream("DebugControlService.png")));
		primaryStage.setTitle("Debug-Control Service");
		primaryStage.setScene(new Scene(root, 700, 600));
		primaryStage.show();

        /* Show an information dialog in case the creation of the JLink GDB server
			* initialization configuration failed at startup*/
		if (!jLinkInitConfigAvailable) {
				/* Update UI */
			Platform.runLater(new Runnable() {
				@Override
				public void run() {
					if (!alert.isShowing()) {
						alert.setTitle("JLink GDB server configuration failed!");
						alert.setHeaderText(null);
						alert.setContentText("Could not create/write configuration file to " + System.getProperty("user.home") + "/.debugcontrolservice/gdbinit");
						Optional<ButtonType> result = alert.showAndWait();
						if (result.isPresent() && result.get() == ButtonType.OK) {
							primaryStage.close();
							primaryStage.fireEvent(
									new WindowEvent(
											primaryStage,
											WindowEvent.WINDOW_CLOSE_REQUEST
									)
							);
						}
					}
				}
			});
		}

        /* Retrieve UI components */
		Circle websocketState = (Circle) primaryStage.getScene().lookup("#browserStateCircle");
		Circle serverState = (Circle) primaryStage.getScene().lookup("#serverStateCircle");
		Circle debugServerState = (Circle) primaryStage.getScene().lookup("#debugServerProcess");
		Label ip = (Label) primaryStage.getScene().lookup("#serverIpLabel");
		Label port = (Label) primaryStage.getScene().lookup("#serverPortLabel");
		Label platform = (Label) primaryStage.getScene().lookup("#developmentPlatform");
		final Label labelJlinkPath = (Label) primaryStage.getScene().lookup("#jlinkpath");
		final Label labelOOCDPath = (Label) primaryStage.getScene().lookup("#oocdpath");
		final TextArea debugConsole = (TextArea) primaryStage.getScene().lookup("#debugConsole");
		//final TextArea debugConsoleOOCD = (TextArea) primaryStage.getScene().lookup("#debugConsoleOOCD");
		final RadioButton radiobtnJlink = (RadioButton) primaryStage.getScene().lookup("#radiobtnJlink");
		final RadioButton radiobtnOOCD = (RadioButton) primaryStage.getScene().lookup("#radiobtnOOCD");
		platFormListView = (ListView) primaryStage.getScene().lookup("#platFormListView");
		TitledPane tpAdvanced = (TitledPane) primaryStage.getScene().lookup("#titledPaneAdvanced");
		final VBox vBoxJLink = (VBox) primaryStage.getScene().lookup("#vBoxJLink");
		final VBox vBoxOOCD = (VBox) primaryStage.getScene().lookup("#vBoxOOCD");
		imageView = (ImageView) primaryStage.getScene().lookup("#loadingImage");
		enumUsbVbox = (VBox) primaryStage.getScene().lookup("#enumUsbVbox");


		InputStream input = getClass().getClassLoader().getResourceAsStream("loading.gif");
		if (input != null)
			imageView.setImage(new Image(input));
		else
			logger.warn("Could not load loading.gif.");

		//GUI components with special properties
		//Add context menu to platFormListView
		platFormListView.setCellFactory(lv -> {
			ListCell<String> cell = new ListCell<>();


			MenuItem connectMenuItemOocd = new MenuItem();
			connectMenuItemOocd.textProperty().bind(Bindings.format("Connect with OOCD"));
			connectMenuItemOocd.setOnAction(event -> {
				String item = cell.getItem();
				ocdssOOCD = new OnChipDebugSystemSoftwareOpenOCD(debugConsole, debugServerState, labelOOCDPath, item.substring(item.indexOf("/#") + 2, item.indexOf("]")), oocdPort, deviceInfo, targetPlatform);
				Thread ocdssOpenOCDThread = new Thread(ocdssOOCD);
				ocdssOpenOCDThread.setDaemon(true);
				ocdssOpenOCDThread.start();
			});


			MenuItem connectMenuItemJlink = new MenuItem();
			connectMenuItemJlink.textProperty().bind(Bindings.format("Connect with JLink"));
			connectMenuItemJlink.setOnAction(event -> {
				String item = cell.getItem();
				ocdssJlink = new OnChipDebugSystemSoftwareJLink(debugConsole, debugServerState, labelJlinkPath, item.substring(item.indexOf("/#") + 2, item.indexOf("]")), jlinkPort, deviceInfo);
				Thread ocdssJlinkThread = new Thread(ocdssJlink);
				ocdssJlinkThread.start();
			});


			//if ( driverTypes.get(new ManufactProductPair(item.substring(0,4), item.substring(5,9))) instanceof DriverType.JLink) {

			cell.textProperty().bind(cell.itemProperty());
			cell.textProperty().addListener((obs, oldv, newv) -> {
				cell.setContextMenu(null);
				ContextMenu contextMenu = new ContextMenu();
				//Only show corresponding connect-option, ragrding the programming device
				if (newv != null && !newv.isEmpty()) {
					if (driverTypes.get(new ManufactProductPair(newv.substring(newv.indexOf("[") + 1, newv.indexOf("[") + 5), newv.substring(newv.indexOf("[") + 6, newv.indexOf("[") + 10))) instanceof DriverType.JLink)
						contextMenu.getItems().add(connectMenuItemJlink);
					if (driverTypes.get(new ManufactProductPair(newv.substring(newv.indexOf("[") + 1, newv.indexOf("[") + 5), newv.substring(newv.indexOf("[") + 6, newv.indexOf("[") + 10))) instanceof DriverType.OpenOCD)
						contextMenu.getItems().add(connectMenuItemOocd);
					cell.setContextMenu(contextMenu);
				}
			});
			cell.emptyProperty().addListener((obs, wasEmpty, isNowEmpty) -> {
				if (isNowEmpty) {
					cell.setContextMenu(null);
				} else {

				}
			});


			return cell;
		});

/*
        // Check if advanced menu should be made visible and resize the height according to last known state
		tpAdvanced.expandedProperty().addListener(new ChangeListener<Boolean>() {
			@Override
			public void changed(ObservableValue<? extends Boolean> observable, Boolean oldValue, Boolean newValue) {
				if (newValue == false) {
					prefHeight = primaryStage.getHeight();
					primaryStage.setHeight(200);
				} else primaryStage.setHeight(prefHeight);
			}
		});
		*/

         /* Check OS and set appropriate default path for the GDB servers */
		if ((OS.contains("linux"))) {
			Platform.runLater(new Runnable() {
				@Override
				public void run() {
					labelJlinkPath.setText("/usr/bin/JLinkGDBServer");
					labelOOCDPath.setText("/usr/local/bin/openocd");
				}
			});
		} else if (OS.contains("windows")) {
			Platform.runLater(new Runnable() {
				@Override
				public void run() {
					labelJlinkPath.setText("C:\\Program Files (x86)\\SEGGER\\JLink_V630k\\JLinkGDBServerCL.exe");
					labelOOCDPath.setText("C:\\Program Files\\openocd 0.9.0\\bin\\openocd.exe");
				}
			});
		} else if (OS.contains("mac")) {
			Platform.runLater(new Runnable() {
				@Override
				public void run() {
					labelJlinkPath.setText("/Applications/SEGGER/JLink/JLinkGDBServer");
					labelOOCDPath.setText("/Applications/GNU ARM Eclipse/OpenOCD/0.8.0-201501181257/bin/openocd");
				}
			});

		} else {
			logger.error("Operating System not supported!");
		}

     	/* Start the platform detector a.k.a. usb hotplug */
		try {
			final UsbServices services = UsbHostManager.getUsbServices();
			logger.info("USB Service Implementation/Impl. version/API version: " + services.getImpDescription() + "/" + services.getImpVersion() + "/" + services.getApiVersion());
			//Start hotplug listener, stays open until application is closed.
			services.addUsbServicesListener(new USBHotPlugListener(platFormListView));

		} catch (Exception e) {
			logger.error("Error: " + e.toString());
		}
		// old code platformdetector - Not needed anymore since USB HotPlugListener - Michael Mazanek
		/*
		platformDetector = new PlatformDetector(platFormListView);
		Thread platformDetectorThread = new Thread(platformDetector);
		if (deviceInfo.size() > 1 && deviceInfo.get(1).equals("universal"))
			platformDetectorThread.start();
		*/

 		/* Start the websocket server thread*/
		websocketServer = new WebSocketServer(websocketState, ip, primaryStage, platform, radiobtnJlink, radiobtnOOCD, debugConsole, debugConsole, labelJlinkPath, port, labelOOCDPath, wsPort, keyStoreFilePath);
		Thread websocketThread = new Thread(websocketServer);
		websocketThread.start();

		/* Start the TCP/IP JLink connection Thread*/
		remotegdbconnector = new RemoteGDBConnector(serverState, websocketServer, startOOCD, targetPlatform);
		Thread remoteGDBConnectorThread = new Thread(remotegdbconnector);
		remoteGDBConnectorThread.start();

		//If started from commandline...
		if (startOOCD == 1) {
			ocdssOOCD = new OnChipDebugSystemSoftwareOpenOCD(debugConsole, debugServerState, labelOOCDPath, deviceInfo.get(0), oocdPort, deviceInfo, targetPlatform);
			Thread ocdssOpenOCDThread = new Thread(ocdssOOCD);
			ocdssOpenOCDThread.setDaemon(true);
			ocdssOpenOCDThread.start();
		}
		if (startJlink == 1) {
			ocdssJlink = new OnChipDebugSystemSoftwareJLink(debugConsole, debugServerState, labelJlinkPath, deviceInfo.get(0), jlinkPort, deviceInfo);
			Thread ocdssJlinkThread = new Thread(ocdssJlink);
			ocdssJlinkThread.start();
		}

		/* Not needed anymore since USB HotPlugListener - Michael Mazanek. partly moved to eventlistener below
        //EventListener for selecting a board manufacturer
		comboBoxDeviceList.valueProperty().addListener(new ChangeListener<String>() {
			@Override
			public void changed(ObservableValue ov, String t, String t1) {
                //Notify the web interface that the board manufacturer has changed
                //and report the status of the corresponding development boards
				if (platformDetector.getProcess() != null && t != null && t1 != null && !t.equals(t1)) {

					if (t1.equals("Infineon")) {
                        // Enable JLink GDB server VBox
						vBoxJLink.setVisible(true);
						vBoxJLink.setMinHeight(Control.USE_COMPUTED_SIZE);
						vBoxJLink.setMinWidth(Control.USE_COMPUTED_SIZE);
						vBoxJLink.setPrefHeight(Control.USE_COMPUTED_SIZE);
						vBoxJLink.setPrefWidth(Control.USE_COMPUTED_SIZE);
                        // Disable OOCD VBox
						vBoxOOCD.setVisible(false);
						vBoxOOCD.setMinHeight(0);
						vBoxOOCD.setMinWidth(0);
						vBoxOOCD.setPrefHeight(0);
						vBoxOOCD.setPrefWidth(0);
                        // Manage console output visibility
						radiobtnJlink.setSelected(true);
						debugConsoleJlink.setVisible(true);
						debugConsoleOOCD.setVisible(false);
                        // Set available devices & send a message to the web IDE
						comboBoxHardware.setItems(platformDetector.getAvailableDevices("Infineon"));
						WebSocketConnectionHandler.ws_sendMsg("xmc4500-selection-changed");
						if (RedirectToJLink.isXmc4500_connected())
							WebSocketConnectionHandler.ws_sendMsg("xmc4500-online");
						else WebSocketConnectionHandler.ws_sendMsg("xmc4500-offline");
					}
					if (t1.equals("TI")) {
                        // Enable OpenOCD VBox
						vBoxOOCD.setVisible(true);
						vBoxOOCD.setMinHeight(Control.USE_COMPUTED_SIZE);
						vBoxOOCD.setMinWidth(Control.USE_COMPUTED_SIZE);
						vBoxOOCD.setPrefHeight(Control.USE_COMPUTED_SIZE);
						vBoxOOCD.setPrefWidth(Control.USE_COMPUTED_SIZE);
                        // Disable JLink GDB server VBox
						vBoxJLink.setVisible(false);
						vBoxJLink.setMinHeight(0);
						vBoxJLink.setMinWidth(0);
						vBoxJLink.setPrefHeight(0);
						vBoxJLink.setPrefWidth(0);
                        // Manage console output visibility
						radiobtnOOCD.setSelected(true);
						debugConsoleJlink.setVisible(false);
						debugConsoleOOCD.setVisible(true);
                        // Set available devices & send a message to the web IDE
						comboBoxHardware.setItems(platformDetector.getAvailableDevices("TI"));
						WebSocketConnectionHandler.ws_sendMsg("tm4c1294xl-selection-changed");
						if (RedirectToOOCD.isLaunchpad_connected())
							WebSocketConnectionHandler.ws_sendMsg("tm4c1294xl-online");
						else WebSocketConnectionHandler.ws_sendMsg("tm4c1294xl-offline");
					}
					target = t1;
				}


			}
		});
		*/

        /* EventListener for board selection based on serial number */
		platFormListView.getSelectionModel().selectedItemProperty().addListener(new ChangeListener<String>() {
			@Override
			public void changed(ObservableValue ov, String t, String t1) {
                /* Restart the appropriate OnChipDebugSystem thread if a new development board is selected */
				if (platformDetector != null && platformDetector.getProcess() != null && t != null && t1 != null && !t.equals(t1)) {
					if (platFormListView.getSelectionModel().getSelectedItem().equals("Infineon")) {
						WebSocketConnectionHandler.ws_sendMsg("xmc4500-selection-changed");
						if (RedirectToJLink.isXmc4500_connected())
							WebSocketConnectionHandler.ws_sendMsg("xmc4500-online");
						else
							WebSocketConnectionHandler.ws_sendMsg("xmc4500-offline");

						OnChipDebugSystemSoftwareJLink.stopJLinkRedirectService();
						OnChipDebugSystemSoftwareJLink.getOCDSProcess().destroy();

						try {
							OnChipDebugSystemSoftwareJLink.getOCDSProcess().waitFor();
						} catch (InterruptedException e) {
							logger.debug("InterruptedException @ OnChipDebugSystemSoftwareJLink.getOCDSProcess().waitFor()", e);
						}
					} else {
						WebSocketConnectionHandler.ws_sendMsg("tm4c1294xl-selection-changed");
						if (RedirectToOOCD.isLaunchpad_connected())
							WebSocketConnectionHandler.ws_sendMsg("tm4c1294xl-online");
						else WebSocketConnectionHandler.ws_sendMsg("tm4c1294xl-offline");

						OnChipDebugSystemSoftwareOpenOCD.getOCDSProcess().destroy();

						try {
							OnChipDebugSystemSoftwareOpenOCD.getOCDSProcess().waitFor();
						} catch (InterruptedException e) {
							logger.debug("InterruptedException @ OnChipDebugSystemSoftwareOpenOCD.getOCDSProcess().waitFor()", e);
						}

					}
				}


			}
		});

        /* Set a custom close event for exiting JLink/OOCD TCP connection and the websocket server */
		primaryStage.setOnCloseRequest(new EventHandler<WindowEvent>() {

			@Override
			public void handle(WindowEvent event) {
				primaryStage.close();

                /* Close websocket & server connection
                 * Exception is raised in Jetty and therfore
                 * needs no finally statement */
				try {
					websocketServer.getServer().stop();
				} catch (Exception e) {
					logger.debug("Exception @ websocketServer.getServer().stop()", e);
				}
				remotegdbconnector.setEnd(true);
				try {
					if (remotegdbconnector.getSocket() != null) remotegdbconnector.getSocket().close();
				} catch (IOException e) {
					logger.debug("IOException @ remotegdbconnector.getSocket().close()", e);
				} finally {
                    /* Close OCDS Threads */
					OnChipDebugSystemSoftwareJLink.stopJLinkRedirectService();
					ocdssJlink.setEnd(true);
					if (OnChipDebugSystemSoftwareJLink.getOCDSProcess() != null)
						OnChipDebugSystemSoftwareJLink.getOCDSProcess().destroy();
					ocdssOOCD.setEnd(true);
					if (OnChipDebugSystemSoftwareOpenOCD.getOCDSProcess() != null)
						OnChipDebugSystemSoftwareOpenOCD.getOCDSProcess().destroy();
					platformDetector.setEnd(true);
					if (platformDetector.getProcess() != null) platformDetector.getProcess().destroy();
				}
			}
		});

	}


	public static void main(String[] args) {

		/* Set the Jetty logger implementation and level (DEBUG | INFO | WARN | IGNORE) */
		System.setProperty("org.eclipse.jetty.util.log.class",
				"org.eclipse.jetty.util.log.JavaUtilLog");
		System.setProperty("org.eclipse.jetty.util.log.class.LEVEL", logLevelJetty);

       /* Instantiate logger */
		logger = (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(Main.class);
		/* Set the log level */
		logger.setLevel(logLevel);

		/* Initialize device info */
		deviceInfo.add(0, null);
		deviceInfo.add(1, null);

		//Add the directory of the JLink DLL to the java library path so it can be found
		String dllPath = System.getProperty("user.dir") + "\\software\\DebugControlService\\Java\\DebugControlService";
		String libPath = System.getProperty("java.library.path");
		System.setProperty("java.library.path",libPath.substring(0,libPath.length()-1) + dllPath + ";.");

		//Set the drivertypes regarding the USB Vendor_ID and Product_ID
		//Key = Vendor_ID (1366) + Product_ID (0101) = 13660101
		driverTypes = new HashMap<>();
		DriverType.JLink driverTypeJLink = new DriverType.JLink();
		DriverType.OpenOCD driverTypeOpenOCD = new DriverType.OpenOCD();
		driverTypes.put(new ManufactProductPair("1366","0101"), driverTypeJLink);
		driverTypes.put(new ManufactProductPair("1cbe","00fd"), driverTypeOpenOCD);

		if (args != null && args.length > 0) {
			for (int i = 0; i < args.length; i++) {

                /* Help */
				if ((args[i].equals("-h") || args[0].equals("--help"))) {
					logger.info("DebugControlService -s <Serial/universal> -e <Websocketport> -j <JLinkport> -o <OOCDport> -sj <StartJLink[ON=1/OFF=0]> -so <StartOOCD[ON=1/OFF=0]> -m <Platform[XMC4500]/[TM4C1294XL]> -wsscert <Keystorepath>");
					System.exit(1);
				}

                /* Enable verbose mode for detailed debug log */
				if ((args[i].equals("-v") || args[0].equals("--verbose"))) {
					logLevel = Level.DEBUG;
					logger.setLevel(logLevel);
				}

                /* Device info & target platform */
				if (args[i].equals("-m") && i + 1 < args.length && !args[i + 1].startsWith("-")) {
					deviceInfo.set(1, args[i + 1]);
					targetPlatform = args[i + 1];
				}

				if (args[i].equals("-s") && i + 1 < args.length && !args[i + 1].startsWith("-"))
					deviceInfo.set(0, args[i + 1]);

                /* Websocket port */
				if (args[i].equals("-e")) {
					try {
						wsPort = Integer.parseInt(args[i + 1]);
					} catch (NumberFormatException e) {
						logger.warn("Websocket Port " + args[i + 1] + " can not be represented as an integer");
						//System.exit(1);
					}
				}
                /* JLink Debug port */
				if (args[i].equals("-j")) {
					try {
						jlinkPort = Integer.parseInt(args[i + 1]);
					} catch (NumberFormatException e) {
						logger.warn("JLink Port " + args[i + 1] + " can not be represented as an integer");
						//System.exit(1);
					}
				}
                /* OOCD Debug Port */
				if (args[i].equals("-o")) {
					try {
						oocdPort = Integer.parseInt(args[i + 1]);
					} catch (NumberFormatException e) {
						logger.warn("OOCD Port " + args[i + 1] + " can not be represented as an integer");
						//System.exit(1);
					}
				}
                /* Start JLink Thread */
				if (args[i].equals("-sj")) {
					try {
						startJlink = Integer.parseInt(args[i + 1]);
					} catch (NumberFormatException e) {
						logger.warn("StartJLink parameter " + args[i + 1] + " can not be represented as an integer");
					}
				}
                /* Start OOCD Thread */
				if (args[i].equals("-so")) {
					try {
						startOOCD = Integer.parseInt(args[i + 1]);
					} catch (NumberFormatException e) {
						logger.warn("StartOOCD parameter " + args[i + 1] + " can not be represented as an integer");
					}
				}
                /* Enable secure WebSocket server */
				if (args[i].equals("-wsscert") && i + 1 < args.length && !args[i + 1].startsWith("-"))
					keyStoreFilePath = args[i + 1];

			}
		}

        /* Create JLink GDB server config directory */
		File gdbInitFile = new File(System.getProperty("user.home") + "/.debugcontrolservice/gdbinit");
		if (!gdbInitFile.getParentFile().exists()) {
			if (!gdbInitFile.getParentFile().mkdirs())
				logger.warn("Could not create config directory for JLink GDB server");
		}
        /* Create/Write initialization configuration for JLink GDB server */
		try {
			gdbInitFile.createNewFile();
			Files.write(Paths.get(System.getProperty("user.home") + "/.debugcontrolservice/gdbinit"), jLinkGDBServerInitConfig.getBytes());
		} catch (IOException e) {
			jLinkInitConfigAvailable = false;
			logger.error("Could not create/write config file for JLink GDB server");
		}

		launch(args);
		//new ToolkitApplicationLauncher().launch(Main.class,args);
	}
}
