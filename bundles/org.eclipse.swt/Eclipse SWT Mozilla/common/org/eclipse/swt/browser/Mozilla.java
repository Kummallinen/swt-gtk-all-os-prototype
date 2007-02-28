/*******************************************************************************
 * Copyright (c) 2003, 2007 IBM Corporation and others.
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the Eclipse Public License v1.0
 * which accompanies this distribution, and is available at
 * http://www.eclipse.org/legal/epl-v10.html
 *
 * Contributors:
 *     IBM Corporation - initial API and implementation
 *******************************************************************************/
package org.eclipse.swt.browser;

import java.io.*;
import java.lang.reflect.*;
import java.util.*;
import org.eclipse.swt.*;
import org.eclipse.swt.widgets.*;
import org.eclipse.swt.graphics.*;
import org.eclipse.swt.internal.*;
import org.eclipse.swt.internal.mozilla.*;
import org.eclipse.swt.layout.*;

class Mozilla extends WebBrowser {
	int /*long*/ embedHandle;
	nsIWebBrowser webBrowser;
	Object webBrowserObject;
	MozillaDelegate delegate;

	/* Interfaces for this Mozilla embedding notification */
	XPCOMObject supports;
	XPCOMObject weakReference;
	XPCOMObject webProgressListener;
	XPCOMObject	webBrowserChrome;
	XPCOMObject webBrowserChromeFocus;
	XPCOMObject embeddingSiteWindow;
	XPCOMObject interfaceRequestor;
	XPCOMObject supportsWeakReference;
	XPCOMObject contextMenuListener;	
	XPCOMObject uriContentListener;
	XPCOMObject tooltipListener;
	int chromeFlags = nsIWebBrowserChrome.CHROME_DEFAULT;
	int refCount = 0;
	int /*long*/ request;
	Point location;
	Point size;
	boolean visible, isChild;
	Shell tip = null;

	static nsIAppShell AppShell;
	static AppFileLocProvider LocationProvider;
	static WindowCreator2 WindowCreator;
	static int BrowserCount;
	static boolean initialized, ignoreDispose;

	/* XULRunner detect constants */
	static final String GRERANGE_LOWER = "1.8"; //$NON-NLS-1$
	static final boolean LowerRangeInclusive = true;
	static final String GRERANGE_UPPER = "1.9"; //$NON-NLS-1$
	static final boolean UpperRangeInclusive = false;

	static final String SEPARATOR_OS = System.getProperty ("file.separator"); //$NON-NLS-1$
	static final String URI_FROMMEMORY = "file:///"; //$NON-NLS-1$
	static final String ABOUT_BLANK = "about:blank"; //$NON-NLS-1$
	static final String PREFERENCE_LANGUAGES = "intl.accept_languages"; //$NON-NLS-1$
	static final String PREFERENCE_CHARSET = "intl.charset.default"; //$NON-NLS-1$
	static final String SEPARATOR_LOCALE = "-"; //$NON-NLS-1$
	static final String TOKENIZER_LOCALE = ","; //$NON-NLS-1$
	static final String PROFILE_DIR = SEPARATOR_OS + "eclipse" + SEPARATOR_OS; //$NON-NLS-1$
	static final String PROFILE_AFTER_CHANGE = "profile-after-change"; //$NON-NLS-1$
	static final String PROFILE_BEFORE_CHANGE = "profile-before-change"; //$NON-NLS-1$
	static final String PROFILE_DO_CHANGE = "profile-do-change"; //$NON-NLS-1$
	static final String SHUTDOWN_PERSIST = "shutdown-persist"; //$NON-NLS-1$
	static final String STARTUP = "startup"; //$NON-NLS-1$

	// TEMPORARY CODE
	static final String XULRUNNER_INITIALIZED = "org.eclipse.swt.browser.XULRunnerInitialized"; //$NON-NLS-1$
	static final String XULRUNNER_PATH = "org.eclipse.swt.browser.XULRunnerPath"; //$NON-NLS-1$

	static {
		MozillaClearSessions = new Runnable () {
			public void run () {
				if (!initialized) return;
				int /*long*/[] result = new int /*long*/[1];
				int rc = XPCOM.NS_GetServiceManager (result);
				if (rc != XPCOM.NS_OK) error (rc);
				if (result[0] == 0) error (XPCOM.NS_NOINTERFACE);
				nsIServiceManager serviceManager = new nsIServiceManager (result[0]);
				result[0] = 0;
				byte[] aContractID = MozillaDelegate.wcsToMbcs (null, XPCOM.NS_COOKIEMANAGER_CONTRACTID, true);
				rc = serviceManager.GetServiceByContractID (aContractID, nsICookieManager.NS_ICOOKIEMANAGER_IID, result);
				if (rc != XPCOM.NS_OK) error (rc);
				if (result[0] == 0) error (XPCOM.NS_NOINTERFACE);
				serviceManager.Release ();

				nsICookieManager manager = new nsICookieManager (result[0]);
				result[0] = 0;
				rc = manager.GetEnumerator (result);
				if (rc != XPCOM.NS_OK) error (rc);
				manager.Release ();

				nsISimpleEnumerator enumerator = new nsISimpleEnumerator (result[0]);
				boolean[] moreElements = new boolean[1];
				rc = enumerator.HasMoreElements (moreElements);
				if (rc != XPCOM.NS_OK) error (rc);
				while (moreElements[0]) {
					result[0] = 0;
					rc = enumerator.GetNext (result);
					if (rc != XPCOM.NS_OK) error (rc);
					nsICookie cookie = new nsICookie (result[0]);
					long[] expires = new long[1];
					rc = cookie.GetExpires (expires);
					if (expires[0] == 0) {
						/* indicates a session cookie */
						int /*long*/ domain = XPCOM.nsEmbedCString_new ();
						int /*long*/ name = XPCOM.nsEmbedCString_new ();
						int /*long*/ path = XPCOM.nsEmbedCString_new ();
						cookie.GetHost (domain);
						cookie.GetName (name);
						cookie.GetPath (path);
						rc = manager.Remove (domain, name, path, false);
						XPCOM.nsEmbedCString_delete (domain);
						XPCOM.nsEmbedCString_delete (name);
						XPCOM.nsEmbedCString_delete (path);
						if (rc != XPCOM.NS_OK) error (rc);
					}
					cookie.Release ();
					rc = enumerator.HasMoreElements (moreElements);
					if (rc != XPCOM.NS_OK) error (rc);
				}
				enumerator.Release ();
			}
		};
	}

public void create (Composite parent, int style) {
	delegate = new MozillaDelegate (browser);
	Display display = parent.getDisplay ();

	int /*long*/[] result = new int /*long*/[1];
	if (!initialized) {
		boolean initLoaded = false;
		String mozillaPath = System.getProperty (XULRUNNER_PATH);
		boolean isXULRunner = false;
		if (mozillaPath == null) {
			try {
				Library.loadLibrary ("swt-xpcominit"); //$NON-NLS-1$
				initLoaded = true;
			} catch (UnsatisfiedLinkError e) {
				try {
					/* 
					 * The initial loadLibrary attempt may have failed as a result of the user's
					 * system not having libstdc++.so.6 installed, so try to load the alternate
					 * swt xpcominit library that depends on libswtc++.so.5 instead.
					 */
					Library.loadLibrary ("swt-xpcominit-gcc3"); //$NON-NLS-1$
					initLoaded = true;
				} catch (UnsatisfiedLinkError ex) {
					/*
					 * If this library still failed to load then do not attempt to detect a
					 * xulrunner to use.  The Browser may still be usable if MOZILLA_FIVE_HOME
					 * points at a GRE. 
					 */
				}
			}
		} else {
			mozillaPath += SEPARATOR_OS + delegate.getLibraryName ();
			String xulrunnerInitialized = System.getProperty (XULRUNNER_INITIALIZED); 
			if ("true".equals (xulrunnerInitialized)) {
				/* 
				 * Another browser has already initialized xulrunner in this process,
				 * so just bind to it instead of trying to initialize a new one.
				 */
				initialized = true;
			}
			isXULRunner = true;
		}

		if (initLoaded) {
			/* attempt to discover a XULRunner to use as the GRE */
			GREVersionRange range = new GREVersionRange ();
			byte[] bytes = MozillaDelegate.wcsToMbcs (null, GRERANGE_LOWER, true);
			int /*long*/ lower = C.malloc (bytes.length);
			C.memmove (lower, bytes, bytes.length);
			range.lower = lower;
			range.lowerInclusive = LowerRangeInclusive;

			bytes = MozillaDelegate.wcsToMbcs (null, GRERANGE_UPPER, true);
			int /*long*/ upper = C.malloc (bytes.length);
			C.memmove (upper, bytes, bytes.length);
			range.upper = upper;
			range.upperInclusive = UpperRangeInclusive;

			int length = XPCOMInit.PATH_MAX;
			int /*long*/ greBuffer = C.malloc (length);
			int /*long*/ propertiesPtr = C.malloc (2 * C.PTR_SIZEOF);
			int rc = XPCOMInit.GRE_GetGREPathWithProperties (range, 1, propertiesPtr, 0, greBuffer, length);
			C.free (lower);
			C.free (upper);
			C.free (propertiesPtr);
			if (rc == XPCOM.NS_OK) {
				/* indicates that a XULRunner was found */
				length = C.strlen (greBuffer);
				bytes = new byte[length];
				C.memmove (bytes, greBuffer, length);
				mozillaPath = new String (MozillaDelegate.mbcsToWcs (null, bytes));
				isXULRunner = mozillaPath.length () > 0;
			}
			C.free (greBuffer);

			/*
			 * Test whether the detected XULRunner can be used as the GRE before loading swt's
			 * XULRunner library.  If it cannot be used then fall back to attempting to use
			 * the GRE pointed to by MOZILLA_FIVE_HOME.
			 * 
			 * One case where this will fail is attempting to use a 64-bit xulrunner while swt
			 * is running in 32-bit mode, or vice versa.
			 */
			byte[] path = MozillaDelegate.wcsToMbcs (null, mozillaPath, true);
			rc = XPCOMInit.XPCOMGlueStartup (path);
			if (rc != XPCOM.NS_OK) {
				isXULRunner = false;	/* failed */
				mozillaPath = mozillaPath.substring (0, mozillaPath.lastIndexOf (SEPARATOR_OS));
				if (Device.DEBUG) System.out.println ("cannot use detected XULRunner: " + mozillaPath); //$NON-NLS-1$
			} else {
				XPCOMInit.XPCOMGlueShutdown ();
			}
		}

		if (isXULRunner) {
			if (Device.DEBUG) System.out.println ("XULRunner path: " + mozillaPath); //$NON-NLS-1$
			try {
				Library.loadLibrary ("swt-xulrunner"); //$NON-NLS-1$
			} catch (UnsatisfiedLinkError e) {
				try {
					/* 
					 * The initial loadLibrary attempt may have failed as a result of the user's
					 * system not having libstdc++.so.6 installed, so try to load the alternate
					 * swt xulrunner library that depends on libswtc++.so.5 instead.
					 */
					Library.loadLibrary ("swt-xulrunner-gcc3"); //$NON-NLS-1$
				} catch (UnsatisfiedLinkError ex) {
					browser.dispose ();
					/*
					 * Print the error from the first failed attempt since at this point it's
					 * known that the failure was not due to the libstdc++.so.6 dependency.
					 */
					SWT.error (SWT.ERROR_NO_HANDLES, e);
				}
			}
			byte[] path = MozillaDelegate.wcsToMbcs (null, mozillaPath, true);
			int rc = XPCOM.XPCOMGlueStartup (path);
			if (rc != XPCOM.NS_OK) {
				browser.dispose ();
				error (rc);
			}

			/*
			 * Remove the trailing xpcom lib name from mozillaPath because the
			 * Mozilla.initialize and NS_InitXPCOM2 invocations require a directory name only.
			 */ 
			mozillaPath = mozillaPath.substring (0, mozillaPath.lastIndexOf (SEPARATOR_OS));
		} else {
			if ((style & SWT.MOZILLA) != 0) {
				browser.dispose ();
				String errorString = (mozillaPath != null && mozillaPath.length () > 0) ?
					" [Failed to use detected XULRunner: " + mozillaPath + "]" :
					" [Could not detect registered XULRunner to use]";	//$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
				SWT.error (SWT.ERROR_NO_HANDLES, null, errorString);
			}

			/* attempt to use the GRE pointed at by MOZILLA_FIVE_HOME */
			int /*long*/ ptr = C.getenv (MozillaDelegate.wcsToMbcs (null, XPCOM.MOZILLA_FIVE_HOME, true));
			if (ptr != 0) {
				int length = C.strlen (ptr);
				byte[] buffer = new byte[length];
				C.memmove (buffer, ptr, length);
				mozillaPath = new String (MozillaDelegate.mbcsToWcs (null, buffer));
			} else {
				browser.dispose ();
				SWT.error (SWT.ERROR_NO_HANDLES, null, " [Unknown Mozilla path (MOZILLA_FIVE_HOME not set)]"); //$NON-NLS-1$
			}
			if (Device.DEBUG) System.out.println ("Mozilla path: " + mozillaPath); //$NON-NLS-1$

			/*
			* Note.  Embedding a Mozilla GTK1.2 causes a crash.  The workaround
			* is to check the version of GTK used by Mozilla by looking for
			* the libwidget_gtk.so library used by Mozilla GTK1.2. Mozilla GTK2
			* uses the libwidget_gtk2.so library.   
			*/
			File file = new File (mozillaPath, "components/libwidget_gtk.so"); //$NON-NLS-1$
			if (file.exists ()) {
				browser.dispose ();
				SWT.error (SWT.ERROR_NO_HANDLES, null, " [Mozilla GTK2 required (GTK1.2 detected)]"); //$NON-NLS-1$							
			}
	
			try {
				Library.loadLibrary ("swt-mozilla"); //$NON-NLS-1$
			} catch (UnsatisfiedLinkError e) {
				try {
					/* 
					 * The initial loadLibrary attempt may have failed as a result of the user's
					 * system not having libstdc++.so.6 installed, so try to load the alternate
					 * swt mozilla library that depends on libswtc++.so.5 instead.
					 */
					Library.loadLibrary ("swt-mozilla-gcc3"); //$NON-NLS-1$
				} catch (UnsatisfiedLinkError ex) {
					browser.dispose ();
					/*
					 * Print the error from the first failed attempt since at this point it's
					 * known that the failure was not due to the libstdc++.so.6 dependency.
					 */
					SWT.error (SWT.ERROR_NO_HANDLES, e);
				}
			}
		}

		if (!initialized) {
			int /*long*/[] retVal = new int /*long*/[1];
			nsEmbedString pathString = new nsEmbedString (mozillaPath);
			int rc = XPCOM.NS_NewLocalFile (pathString.getAddress (), true, retVal);
			pathString.dispose ();
			if (rc != XPCOM.NS_OK) {
				browser.dispose ();
				error (rc);
			}
			if (retVal[0] == 0) {
				browser.dispose ();
				error (XPCOM.NS_ERROR_NULL_POINTER);
			}

			LocationProvider = new AppFileLocProvider (mozillaPath);
			LocationProvider.AddRef ();

			nsIFile localFile = new nsILocalFile (retVal[0]);
			rc = XPCOM.NS_InitXPCOM2 (0, localFile.getAddress(), LocationProvider.getAddress ());
			localFile.Release ();
			if (rc != XPCOM.NS_OK) {
				browser.dispose ();
				SWT.error (SWT.ERROR_NO_HANDLES, null, " [MOZILLA_FIVE_HOME may not point at an embeddable GRE] [NS_InitEmbedding " + mozillaPath + " error " + rc + "]"); //$NON-NLS-1$ //$NON-NLS-2$ //$NON-NLS-3$
			}
			System.setProperty (XULRUNNER_PATH, mozillaPath); //$NON-NLS-1$
			System.setProperty (XULRUNNER_INITIALIZED, "true"); //$NON-NLS-1$
		}

		/* If JavaXPCOM is detected then attempt to initialize it with the XULRunner being used */
		if (isXULRunner) {
			try {
				Class clazz = Class.forName ("org.mozilla.xpcom.Mozilla"); //$NON-NLS-1$
				Method method = clazz.getMethod ("getInstance", new Class[0]); //$NON-NLS-1$
				Object mozilla = method.invoke (null, new Object[0]);
				method = clazz.getMethod ("getComponentManager", new Class[0]); //$NON-NLS-1$
				try {
					method.invoke (mozilla, new Object[0]);
				} catch (InvocationTargetException e) {
					/* indicates that JavaXPCOM has not been initialized yet */
					method = clazz.getMethod ("initialize", new Class[] {Class.forName ("java.io.File")}); //$NON-NLS-1$ //$NON-NLS-2$
					method.invoke (mozilla, new Object[] {new File (mozillaPath)});
				}
			} catch (ClassNotFoundException e) {
				/* JavaXPCOM is not on the classpath */
			} catch (NoSuchMethodException e) {
				/* the JavaXPCOM on the classpath does not implement initialize() */
			} catch (IllegalArgumentException e) {
			} catch (IllegalAccessException e) {
			} catch (InvocationTargetException e) {
			}
		}

		int rc = XPCOM.NS_GetComponentManager (result);
		if (rc != XPCOM.NS_OK) {
			browser.dispose ();
			error (rc);
		}
		if (result[0] == 0) {
			browser.dispose ();
			error (XPCOM.NS_NOINTERFACE);
		}
		
		nsIComponentManager componentManager = new nsIComponentManager (result[0]);
		result[0] = 0;
		/* nsIAppShell is discontinued as of xulrunner 1.9, so do not fail if it is not found */
		rc = componentManager.CreateInstance (XPCOM.NS_APPSHELL_CID, 0, nsIAppShell.NS_IAPPSHELL_IID, result);
		if (rc != XPCOM.NS_ERROR_NO_INTERFACE) {
			if (rc != XPCOM.NS_OK) {
				browser.dispose ();
				error (rc);
			}
			if (result[0] == 0) {
				browser.dispose ();
				error (XPCOM.NS_NOINTERFACE);
			}

			AppShell = new nsIAppShell (result[0]);
			rc = AppShell.Create (0, null);
			if (rc != XPCOM.NS_OK) {
				browser.dispose ();
				error (rc);
			}
			rc = AppShell.Spinup ();
			if (rc != XPCOM.NS_OK) {
				browser.dispose ();
				error (rc);
			}
		}
		result[0] = 0;

		WindowCreator = new WindowCreator2 ();
		WindowCreator.AddRef ();
		
		rc = XPCOM.NS_GetServiceManager (result);
		if (rc != XPCOM.NS_OK) {
			browser.dispose ();
			error (rc);
		}
		if (result[0] == 0) {
			browser.dispose ();
			error (XPCOM.NS_NOINTERFACE);
		}
		
		nsIServiceManager serviceManager = new nsIServiceManager (result[0]);
		result[0] = 0;		
		byte[] aContractID = MozillaDelegate.wcsToMbcs (null, XPCOM.NS_WINDOWWATCHER_CONTRACTID, true);
		rc = serviceManager.GetServiceByContractID (aContractID, nsIWindowWatcher.NS_IWINDOWWATCHER_IID, result);
		if (rc != XPCOM.NS_OK) {
			browser.dispose ();
			error (rc);
		}
		if (result[0] == 0) {
			browser.dispose ();
			error (XPCOM.NS_NOINTERFACE);		
		}

		nsIWindowWatcher windowWatcher = new nsIWindowWatcher (result[0]);
		result[0] = 0;
		rc = windowWatcher.SetWindowCreator (WindowCreator.getAddress());
		if (rc != XPCOM.NS_OK) {
			browser.dispose ();
			error (rc);
		}
		windowWatcher.Release ();

		/* compute the profile directory and set it on the AppFileLocProvider */
		byte[] buffer = MozillaDelegate.wcsToMbcs (null, XPCOM.NS_DIRECTORYSERVICE_CONTRACTID, true);
		rc = serviceManager.GetServiceByContractID (buffer, nsIDirectoryService.NS_IDIRECTORYSERVICE_IID, result);
		if (rc != XPCOM.NS_OK) {
			browser.dispose ();
			error (rc);
		}
		if (result[0] == 0) {
			browser.dispose ();
			error (XPCOM.NS_NOINTERFACE);
		}

		nsIDirectoryService directoryService = new nsIDirectoryService (result[0]);
		result[0] = 0;
		rc = directoryService.QueryInterface (nsIProperties.NS_IPROPERTIES_IID, result);
		if (rc != XPCOM.NS_OK) {
			browser.dispose ();
			error (rc);
		}
		if (result[0] == 0) {
			browser.dispose ();
			error (XPCOM.NS_NOINTERFACE);
		}
		directoryService.Release ();

		nsIProperties properties = new nsIProperties (result[0]);
		result[0] = 0;
		buffer = MozillaDelegate.wcsToMbcs (null, XPCOM.NS_APP_APPLICATION_REGISTRY_DIR, true);
		rc = properties.Get (buffer, nsIFile.NS_IFILE_IID, result);
		if (rc != XPCOM.NS_OK) {
			browser.dispose ();
			error (rc);
		}
		if (result[0] == 0) {
			browser.dispose ();
			error (XPCOM.NS_NOINTERFACE);
		}
		properties.Release ();

		nsIFile profileDir = new nsIFile (result[0]);
		result[0] = 0;
		int /*long*/ path = XPCOM.nsEmbedCString_new ();
		rc = profileDir.GetNativePath (path);
		if (rc != XPCOM.NS_OK) {
			browser.dispose ();
			error (rc);
		}
		int length = XPCOM.nsEmbedCString_Length (path);
		int /*long*/ ptr = XPCOM.nsEmbedCString_get (path);
		buffer = new byte [length];
		XPCOM.memmove (buffer, ptr, length);
		String profilePath = new String (MozillaDelegate.mbcsToWcs (null, buffer)) + PROFILE_DIR;
		LocationProvider.setProfilePath (profilePath);
		XPCOM.nsEmbedCString_delete (path);
		profileDir.Release ();

		/* notify observers of a new profile directory being used */
		buffer = MozillaDelegate.wcsToMbcs (null, XPCOM.NS_OBSERVER_CONTRACTID, true);
		rc = serviceManager.GetServiceByContractID (buffer, nsIObserverService.NS_IOBSERVERSERVICE_IID, result);
		if (rc != XPCOM.NS_OK) {
			browser.dispose ();
			error (rc);
		}
		if (result[0] == 0) {
			browser.dispose ();
			error (XPCOM.NS_NOINTERFACE);
		}

		nsIObserverService observerService = new nsIObserverService (result[0]);
		result[0] = 0;
		buffer = MozillaDelegate.wcsToMbcs (null, PROFILE_DO_CHANGE, true);
		length = STARTUP.length ();
		char[] chars = new char [length + 1];
		STARTUP.getChars (0, length, chars, 0);
		rc = observerService.NotifyObservers (0, buffer, chars);
		if (rc != XPCOM.NS_OK) {
			browser.dispose ();
			error (rc);
		}
		buffer = MozillaDelegate.wcsToMbcs (null, PROFILE_AFTER_CHANGE, true);
		rc = observerService.NotifyObservers (0, buffer, chars);
		if (rc != XPCOM.NS_OK) {
			browser.dispose ();
			error (rc);
		}
		observerService.Release ();

		display.addListener (SWT.Dispose, new Listener () {
			public void handleEvent (Event event) {
				int /*long*/[] result = new int /*long*/[1];
				int rc = XPCOM.NS_GetServiceManager (result);
				if (rc != XPCOM.NS_OK) error (rc);
				if (result[0] == 0) error (XPCOM.NS_NOINTERFACE);

				nsIServiceManager serviceManager = new nsIServiceManager (result[0]);
				result[0] = 0;		
				byte[] buffer = MozillaDelegate.wcsToMbcs (null, XPCOM.NS_OBSERVER_CONTRACTID, true);
				rc = serviceManager.GetServiceByContractID (buffer, nsIObserverService.NS_IOBSERVERSERVICE_IID, result);
				if (rc != XPCOM.NS_OK) error (rc);
				if (result[0] == 0) error (XPCOM.NS_NOINTERFACE);
				serviceManager.Release ();

				nsIObserverService observerService = new nsIObserverService (result[0]);
				result[0] = 0;
				buffer = MozillaDelegate.wcsToMbcs (null, PROFILE_BEFORE_CHANGE, true);
				int length = SHUTDOWN_PERSIST.length ();
				char[] chars = new char [length + 1];
				SHUTDOWN_PERSIST.getChars (0, length, chars, 0);
				rc = observerService.NotifyObservers (0, buffer, chars);
				if (rc != XPCOM.NS_OK) error (rc);
				observerService.Release ();
			}
		});

		/*
		 * As a result of using a common profile the user cannot change their locale
		 * and charset.  The fix for this is to set mozilla's locale and charset
		 * preference values according to the user's current locale and charset.
		 */
		aContractID = MozillaDelegate.wcsToMbcs (null, XPCOM.NS_PREFSERVICE_CONTRACTID, true);
		rc = serviceManager.GetServiceByContractID (aContractID, nsIPrefService.NS_IPREFSERVICE_IID, result);
		serviceManager.Release ();
		if (rc != XPCOM.NS_OK) {
			browser.dispose ();
			error (rc);
		}
		if (result[0] == 0) {
			browser.dispose ();
			error (XPCOM.NS_NOINTERFACE);
		}

		nsIPrefService prefService = new nsIPrefService (result[0]);
		result[0] = 0;
		buffer = new byte[1];
		rc = prefService.GetBranch (buffer, result);	/* empty buffer denotes root preference level */
		prefService.Release ();
		if (rc != XPCOM.NS_OK) {
			browser.dispose ();
			error (rc);
		}
		if (result[0] == 0) {
			browser.dispose ();
			error (XPCOM.NS_NOINTERFACE);
		}

		nsIPrefBranch prefBranch = new nsIPrefBranch (result[0]);
		result[0] = 0;

		/* get Mozilla's current locale preference value */
		String prefLocales = null;
		nsIPrefLocalizedString localizedString = null;
		buffer = MozillaDelegate.wcsToMbcs (null, PREFERENCE_LANGUAGES, true);
		rc = prefBranch.GetComplexValue (buffer, nsIPrefLocalizedString.NS_IPREFLOCALIZEDSTRING_IID, result);
		/* 
		 * Feature of Debian.  For some reason attempting to query for the current locale
		 * preference fails on Debian.  The workaround for this is to assume a value of
		 * "en-us,en" since this is typically the default value when mozilla is used without
		 * a profile.
		 */
		if (rc != XPCOM.NS_OK) {
			prefLocales = "en-us,en" + TOKENIZER_LOCALE;	//$NON-NLS-1$
		} else {
			if (result[0] == 0) {
				browser.dispose ();
				error (XPCOM.NS_NOINTERFACE);
			}
			localizedString = new nsIPrefLocalizedString (result[0]);
			result[0] = 0;
			rc = localizedString.ToString (result);
			if (rc != XPCOM.NS_OK) {
				browser.dispose ();
				error (rc);
			}
			if (result[0] == 0) {
				browser.dispose ();
				error (XPCOM.NS_NOINTERFACE);
			}
			length = XPCOM.strlen_PRUnichar (result[0]);
			char[] dest = new char[length];
			XPCOM.memmove (dest, result[0], length * 2);
			prefLocales = new String (dest) + TOKENIZER_LOCALE;
		}
		result[0] = 0;

		/*
		 * construct the new locale preference value by prepending the
		 * user's current locale and language to the original value 
		 */
		Locale locale = Locale.getDefault ();
		String language = locale.getLanguage ();
		String country = locale.getCountry ();
		StringBuffer stringBuffer = new StringBuffer (language);
		stringBuffer.append (SEPARATOR_LOCALE);
		stringBuffer.append (country.toLowerCase ());
		stringBuffer.append (TOKENIZER_LOCALE);
		stringBuffer.append (language);
		stringBuffer.append (TOKENIZER_LOCALE);
		String newLocales = stringBuffer.toString ();
		StringTokenizer tokenzier = new StringTokenizer (prefLocales, TOKENIZER_LOCALE);
		while (tokenzier.hasMoreTokens ()) {
			String token = (tokenzier.nextToken () + TOKENIZER_LOCALE).trim ();
			/* ensure that duplicate locale values are not added */
			if (newLocales.indexOf (token) == -1) {
				stringBuffer.append (token);
			}
		}
		newLocales = stringBuffer.toString ();
		if (!newLocales.equals (prefLocales)) {
			/* write the new locale value */
			newLocales = newLocales.substring (0, newLocales.length () - TOKENIZER_LOCALE.length ()); /* remove trailing tokenizer */
			length = newLocales.length ();
			char[] charBuffer = new char[length + 1];
			newLocales.getChars (0, length, charBuffer, 0);
			if (localizedString == null) {
				byte[] contractID = MozillaDelegate.wcsToMbcs (null, XPCOM.NS_PREFLOCALIZEDSTRING_CONTRACTID, true);
				rc = componentManager.CreateInstanceByContractID (contractID, 0, nsIPrefLocalizedString.NS_IPREFLOCALIZEDSTRING_IID, result);
				if (rc != XPCOM.NS_OK) {
					browser.dispose ();
					error (rc);
				}
				if (result[0] == 0) {
					browser.dispose ();
					error (XPCOM.NS_NOINTERFACE);
				}
				localizedString = new nsIPrefLocalizedString (result[0]);
				result[0] = 0;
			}
			localizedString.SetDataWithLength (length, charBuffer);
			rc = prefBranch.SetComplexValue (buffer, nsIPrefLocalizedString.NS_IPREFLOCALIZEDSTRING_IID, localizedString.getAddress());
		}
		if (localizedString != null) {
			localizedString.Release ();
			localizedString = null;
		}

		/* get Mozilla's current charset preference value */
		String prefCharset = null;
		buffer = MozillaDelegate.wcsToMbcs (null, PREFERENCE_CHARSET, true);
		rc = prefBranch.GetComplexValue (buffer, nsIPrefLocalizedString.NS_IPREFLOCALIZEDSTRING_IID, result);
		/* 
		 * Feature of Debian.  For some reason attempting to query for the current charset
		 * preference fails on Debian.  The workaround for this is to assume a value of
		 * "ISO-8859-1" since this is typically the default value when mozilla is used
		 * without a profile.
		 */
		if (rc != XPCOM.NS_OK) {
			prefCharset = "ISO-8859-1";	//$NON_NLS-1$
		} else {
			if (result[0] == 0) {
				browser.dispose ();
				error (XPCOM.NS_NOINTERFACE);
			}
			localizedString = new nsIPrefLocalizedString (result[0]);
			result[0] = 0;
			rc = localizedString.ToString (result);
			if (rc != XPCOM.NS_OK) {
				browser.dispose ();
				error (rc);
			}
			if (result[0] == 0) {
				browser.dispose ();
				error (XPCOM.NS_NOINTERFACE);
			}
			length = XPCOM.strlen_PRUnichar (result[0]);
			char[] dest = new char[length];
			XPCOM.memmove (dest, result[0], length * 2);
			prefCharset = new String (dest);
		}
		result[0] = 0;

		String newCharset = System.getProperty ("file.encoding");	// $NON-NLS-1$
		if (!newCharset.equals (prefCharset)) {
			/* write the new charset value */
			length = newCharset.length ();
			char[] charBuffer = new char[length + 1];
			newCharset.getChars (0, length, charBuffer, 0);
			if (localizedString == null) {
				byte[] contractID = MozillaDelegate.wcsToMbcs (null, XPCOM.NS_PREFLOCALIZEDSTRING_CONTRACTID, true);
				rc = componentManager.CreateInstanceByContractID (contractID, 0, nsIPrefLocalizedString.NS_IPREFLOCALIZEDSTRING_IID, result);
				if (rc != XPCOM.NS_OK) {
					browser.dispose ();
					error (rc);
				}
				if (result[0] == 0) {
					browser.dispose ();
					error (XPCOM.NS_NOINTERFACE);
				}
				localizedString = new nsIPrefLocalizedString (result[0]);
				result[0] = 0;
			}
			localizedString.SetDataWithLength (length, charBuffer);
			rc = prefBranch.SetComplexValue (buffer, nsIPrefLocalizedString.NS_IPREFLOCALIZEDSTRING_IID, localizedString.getAddress ());
		}
		if (localizedString != null) localizedString.Release ();
		prefBranch.Release ();

		PromptServiceFactory factory = new PromptServiceFactory ();
		factory.AddRef ();

		rc = componentManager.QueryInterface (nsIComponentRegistrar.NS_ICOMPONENTREGISTRAR_IID, result);
		if (rc != XPCOM.NS_OK) {
			browser.dispose ();
			error (rc);
		}
		if (result[0] == 0) {
			browser.dispose ();
			error (XPCOM.NS_NOINTERFACE);
		}
		
		nsIComponentRegistrar componentRegistrar = new nsIComponentRegistrar (result[0]);
		result[0] = 0;
		aContractID = MozillaDelegate.wcsToMbcs (null, XPCOM.NS_PROMPTSERVICE_CONTRACTID, true); 
		byte[] aClassName = MozillaDelegate.wcsToMbcs (null, "Prompt Service", true); //$NON-NLS-1$
		rc = componentRegistrar.RegisterFactory (XPCOM.NS_PROMPTSERVICE_CID, aClassName, aContractID, factory.getAddress ());
		if (rc != XPCOM.NS_OK) {
			browser.dispose ();
			error (rc);
		}
		factory.Release ();
		
		HelperAppLauncherDialogFactory dialogFactory = new HelperAppLauncherDialogFactory ();
		dialogFactory.AddRef ();
		aContractID = MozillaDelegate.wcsToMbcs (null, XPCOM.NS_HELPERAPPLAUNCHERDIALOG_CONTRACTID, true);
		aClassName = MozillaDelegate.wcsToMbcs (null, "Helper App Launcher Dialog", true); //$NON-NLS-1$
		rc = componentRegistrar.RegisterFactory (XPCOM.NS_HELPERAPPLAUNCHERDIALOG_CID, aClassName, aContractID, dialogFactory.getAddress ());
		if (rc != XPCOM.NS_OK) {
			browser.dispose ();
			error (rc);
		}
		dialogFactory.Release ();

		DownloadFactory downloadFactory = new DownloadFactory ();
		downloadFactory.AddRef ();
		aContractID = MozillaDelegate.wcsToMbcs (null, XPCOM.NS_DOWNLOAD_CONTRACTID, true);
		aClassName = MozillaDelegate.wcsToMbcs (null, "Download", true); //$NON-NLS-1$
		rc = componentRegistrar.RegisterFactory (XPCOM.NS_DOWNLOAD_CID, aClassName, aContractID, downloadFactory.getAddress ());
		if (rc != XPCOM.NS_OK) {
			browser.dispose ();
			error (rc);
		}
		downloadFactory.Release ();

		DownloadFactory_1_8 downloadFactory_1_8 = new DownloadFactory_1_8 ();
		downloadFactory_1_8.AddRef ();
		aContractID = MozillaDelegate.wcsToMbcs (null, XPCOM.NS_TRANSFER_CONTRACTID, true);
		aClassName = MozillaDelegate.wcsToMbcs (null, "Transfer", true); //$NON-NLS-1$
		rc = componentRegistrar.RegisterFactory (XPCOM.NS_DOWNLOAD_CID, aClassName, aContractID, downloadFactory_1_8.getAddress ());
		if (rc != XPCOM.NS_OK) {
			browser.dispose ();
			error (rc);
		}
		downloadFactory_1_8.Release ();

		FilePickerFactory pickerFactory = new FilePickerFactory ();
		pickerFactory.AddRef ();
		aContractID = MozillaDelegate.wcsToMbcs (null, XPCOM.NS_FILEPICKER_CONTRACTID, true);
		aClassName = MozillaDelegate.wcsToMbcs (null, "FilePicker", true); //$NON-NLS-1$
		rc = componentRegistrar.RegisterFactory (XPCOM.NS_FILEPICKER_CID, aClassName, aContractID, pickerFactory.getAddress ());
		if (rc != XPCOM.NS_OK) {
			browser.dispose ();
			error (rc);
		}
		pickerFactory.Release ();

		componentRegistrar.Release ();
		componentManager.Release ();
		initialized = true;
	}

	BrowserCount++;
	int rc = XPCOM.NS_GetComponentManager (result);
	if (rc != XPCOM.NS_OK) {
		browser.dispose ();
		error (rc);
	}
	if (result[0] == 0) {
		browser.dispose ();
		error (XPCOM.NS_NOINTERFACE);
	}
	
	nsIComponentManager componentManager = new nsIComponentManager (result[0]);
	result[0] = 0;
	nsID NS_IWEBBROWSER_CID = new nsID ("F1EAC761-87E9-11d3-AF80-00A024FFC08C"); //$NON-NLS-1$
	rc = componentManager.CreateInstance (NS_IWEBBROWSER_CID, 0, nsIWebBrowser.NS_IWEBBROWSER_IID, result);
	if (rc != XPCOM.NS_OK) {
		browser.dispose ();
		error (rc);
	}
	if (result[0] == 0) {
		browser.dispose ();
		error (XPCOM.NS_NOINTERFACE);	
	}
	componentManager.Release ();
	
	webBrowser = new nsIWebBrowser (result[0]);

	createCOMInterfaces ();
	AddRef ();

	rc = webBrowser.SetContainerWindow (webBrowserChrome.getAddress());
	if (rc != XPCOM.NS_OK) {
		browser.dispose ();
		error (rc);
	}
			
	rc = webBrowser.QueryInterface (nsIBaseWindow.NS_IBASEWINDOW_IID, result);
	if (rc != XPCOM.NS_OK) {
		browser.dispose ();
		error (rc);
	}
	if (result[0] == 0) {
		browser.dispose ();
		error (XPCOM.NS_ERROR_NO_INTERFACE);
	}
	
	nsIBaseWindow baseWindow = new nsIBaseWindow (result[0]);
	result[0] = 0;
	Rectangle rect = browser.getClientArea ();
	if (rect.isEmpty ()) {
		rect.width = 1;
		rect.height = 1;
	}

	embedHandle = delegate.getHandle ();

	rc = baseWindow.InitWindow (embedHandle, 0, 0, 0, rect.width, rect.height);
	if (rc != XPCOM.NS_OK) {
		browser.dispose ();
		error (XPCOM.NS_ERROR_FAILURE);
	}
	rc = baseWindow.Create ();
	if (rc != XPCOM.NS_OK) {
		browser.dispose ();
		error (XPCOM.NS_ERROR_FAILURE);
	}
	rc = baseWindow.SetVisibility (true);
	if (rc != XPCOM.NS_OK) {
		browser.dispose ();
		error (XPCOM.NS_ERROR_FAILURE);
	}
	baseWindow.Release ();

	rc = webBrowser.AddWebBrowserListener (weakReference.getAddress (), nsIWebProgressListener.NS_IWEBPROGRESSLISTENER_IID);
	if (rc != XPCOM.NS_OK) {
		browser.dispose ();
		error (rc);
	}

	rc = webBrowser.SetParentURIContentListener (uriContentListener.getAddress ());
	if (rc != XPCOM.NS_OK) {
		browser.dispose ();
		error (rc);
	}

	Listener listener = new Listener () {
		public void handleEvent (Event event) {
			switch (event.type) {
				case SWT.Dispose: {
					/* make this handler run after other dispose listeners */
					if (ignoreDispose) {
						ignoreDispose = false;
						break;
					}
					ignoreDispose = true;
					browser.notifyListeners (event.type, event);
					event.type = SWT.NONE;
					onDispose (event.display);
					break;
				}
				case SWT.Resize: onResize (); break;
				case SWT.FocusIn: Activate (); break;
				case SWT.Activate: Activate (); break;
				case SWT.Deactivate: {
					Display display = event.display;
					if (Mozilla.this.browser == display.getFocusControl ()) Deactivate ();
					break;
				}
				case SWT.Show: {
					/*
					* Feature on GTK Mozilla.  Mozilla does not show up when
					* its container (a GTK fixed handle) is made visible
					* after having been hidden.  The workaround is to reset
					* its size after the container has been made visible. 
					*/
					Display display = event.display;
					display.asyncExec(new Runnable () {
						public void run() {
							if (browser.isDisposed ()) return;
							onResize ();
						}
					});
					break;
				}
			}
		}
	};	
	int[] folderEvents = new int[] {
		SWT.Dispose,
		SWT.Resize,  
		SWT.FocusIn,
		SWT.Activate,
		SWT.Deactivate,
		SWT.Show,
		SWT.KeyDown		// needed to make browser traversable
	};
	for (int i = 0; i < folderEvents.length; i++) {
		browser.addListener (folderEvents[i], listener);
	}
}

public boolean back () {
	int /*long*/[] result = new int /*long*/[1];
	int rc = webBrowser.QueryInterface (nsIWebNavigation.NS_IWEBNAVIGATION_IID, result);
	if (rc != XPCOM.NS_OK) error (rc);
	if (result[0] == 0) error (XPCOM.NS_ERROR_NO_INTERFACE);
	
	nsIWebNavigation webNavigation = new nsIWebNavigation (result[0]);		 	
	rc = webNavigation.GoBack ();	
	webNavigation.Release ();
	return rc == XPCOM.NS_OK;
}

void createCOMInterfaces () {
	// Create each of the interfaces that this object implements
	supports = new XPCOMObject (new int[] {2, 0, 0}) {
		public int /*long*/ method0 (int /*long*/[] args) {return QueryInterface (args[0], args[1]);}
		public int /*long*/ method1 (int /*long*/[] args) {return AddRef ();}
		public int /*long*/ method2 (int /*long*/[] args) {return Release ();}
	};
	
	weakReference = new XPCOMObject (new int[] {2, 0, 0, 2}) {
		public int /*long*/ method0 (int /*long*/[] args) {return QueryInterface (args[0], args[1]);}
		public int /*long*/ method1 (int /*long*/[] args) {return AddRef ();}
		public int /*long*/ method2 (int /*long*/[] args) {return Release ();}
		public int /*long*/ method3 (int /*long*/[] args) {return QueryReferent (args[0], args[1]);}
	};

	webProgressListener = new XPCOMObject (new int[] {2, 0, 0, 4, 6, 3, 4, 3}) {
		public int /*long*/ method0 (int /*long*/[] args) {return QueryInterface (args[0], args[1]);}
		public int /*long*/ method1 (int /*long*/[] args) {return AddRef ();}
		public int /*long*/ method2 (int /*long*/[] args) {return Release ();}
		public int /*long*/ method3 (int /*long*/[] args) {return OnStateChange (args[0], args[1], args[2],args[3]);}
		public int /*long*/ method4 (int /*long*/[] args) {return OnProgressChange (args[0], args[1], args[2], args[3], args[4], args[5]);}
		public int /*long*/ method5 (int /*long*/[] args) {return OnLocationChange (args[0], args[1], args[2]);}
		public int /*long*/ method6 (int /*long*/[] args) {return OnStatusChange (args[0], args[1], args[2], args[3]);}
		public int /*long*/ method7 (int /*long*/[] args) {return OnSecurityChange (args[0], args[1], args[2]);}
	};
	
	webBrowserChrome = new XPCOMObject (new int[] {2, 0, 0, 2, 1, 1, 1, 1, 0, 2, 0, 1, 1}) {
		public int /*long*/ method0 (int /*long*/[] args) {return QueryInterface (args[0], args[1]);}
		public int /*long*/ method1 (int /*long*/[] args) {return AddRef ();}
		public int /*long*/ method2 (int /*long*/[] args) {return Release ();}
		public int /*long*/ method3 (int /*long*/[] args) {return SetStatus (args[0], args[1]);}
		public int /*long*/ method4 (int /*long*/[] args) {return GetWebBrowser (args[0]);}
		public int /*long*/ method5 (int /*long*/[] args) {return SetWebBrowser (args[0]);}
		public int /*long*/ method6 (int /*long*/[] args) {return GetChromeFlags (args[0]);}
		public int /*long*/ method7 (int /*long*/[] args) {return SetChromeFlags (args[0]);}
		public int /*long*/ method8 (int /*long*/[] args) {return DestroyBrowserWindow ();}
		public int /*long*/ method9 (int /*long*/[] args) {return SizeBrowserTo (args[0], args[1]);}
		public int /*long*/ method10 (int /*long*/[] args) {return ShowAsModal ();}
		public int /*long*/ method11 (int /*long*/[] args) {return IsWindowModal (args[0]);}
		public int /*long*/ method12 (int /*long*/[] args) {return ExitModalEventLoop (args[0]);}
	};
	
	webBrowserChromeFocus = new XPCOMObject (new int[] {2, 0, 0, 0, 0}) {
		public int /*long*/ method0 (int /*long*/[] args) {return QueryInterface (args[0], args[1]);}
		public int /*long*/ method1 (int /*long*/[] args) {return AddRef ();}
		public int /*long*/ method2 (int /*long*/[] args) {return Release ();}
		public int /*long*/ method3 (int /*long*/[] args) {return FocusNextElement ();}
		public int /*long*/ method4 (int /*long*/[] args) {return FocusPrevElement ();}
	};
		
	embeddingSiteWindow = new XPCOMObject (new int[] {2, 0, 0, 5, 5, 0, 1, 1, 1, 1, 1}) {
		public int /*long*/ method0 (int /*long*/[] args) {return QueryInterface (args[0], args[1]);}
		public int /*long*/ method1 (int /*long*/[] args) {return AddRef ();}
		public int /*long*/ method2 (int /*long*/[] args) {return Release ();}
		public int /*long*/ method3 (int /*long*/[] args) {return SetDimensions (args[0], args[1], args[2], args[3], args[4]);}
		public int /*long*/ method4 (int /*long*/[] args) {return GetDimensions (args[0], args[1], args[2], args[3], args[4]);}
		public int /*long*/ method5 (int /*long*/[] args) {return SetFocus ();}
		public int /*long*/ method6 (int /*long*/[] args) {return GetVisibility (args[0]);}
		public int /*long*/ method7 (int /*long*/[] args) {return SetVisibility (args[0]);}
		public int /*long*/ method8 (int /*long*/[] args) {return GetTitle (args[0]);}
		public int /*long*/ method9 (int /*long*/[] args) {return SetTitle (args[0]);}
		public int /*long*/ method10 (int /*long*/[] args) {return GetSiteWindow (args[0]);}
	};
	
	interfaceRequestor = new XPCOMObject (new int[] {2, 0, 0, 2} ){
		public int /*long*/ method0 (int /*long*/[] args) {return QueryInterface (args[0], args[1]);}
		public int /*long*/ method1 (int /*long*/[] args) {return AddRef ();}
		public int /*long*/ method2 (int /*long*/[] args) {return Release ();}
		public int /*long*/ method3 (int /*long*/[] args) {return GetInterface (args[0], args[1]);}
	};
		
	supportsWeakReference = new XPCOMObject (new int[] {2, 0, 0, 1}) {
		public int /*long*/ method0 (int /*long*/[] args) {return QueryInterface (args[0], args[1]);}
		public int /*long*/ method1 (int /*long*/[] args) {return AddRef ();}
		public int /*long*/ method2 (int /*long*/[] args) {return Release ();}
		public int /*long*/ method3 (int /*long*/[] args) {return GetWeakReference (args[0]);}
	};
	
	contextMenuListener = new XPCOMObject (new int[] {2, 0, 0, 3}) {
		public int /*long*/ method0 (int /*long*/[] args) {return QueryInterface (args[0], args[1]);}
		public int /*long*/ method1 (int /*long*/[] args) {return AddRef ();}
		public int /*long*/ method2 (int /*long*/[] args) {return Release ();}
		public int /*long*/ method3 (int /*long*/[] args) {return OnShowContextMenu (args[0],args[1],args[2]);}
	};
	
	uriContentListener = new XPCOMObject (new int[] {2, 0, 0, 2, 5, 3, 4, 1, 1, 1, 1}) {
		public int /*long*/ method0 (int /*long*/[] args) {return QueryInterface (args[0], args[1]);}
		public int /*long*/ method1 (int /*long*/[] args) {return AddRef ();}
		public int /*long*/ method2 (int /*long*/[] args) {return Release ();}
		public int /*long*/ method3 (int /*long*/[] args) {return OnStartURIOpen (args[0], args[1]);}
		public int /*long*/ method4 (int /*long*/[] args) {return DoContent (args[0], args[1], args[2], args[3], args[4]);}
		public int /*long*/ method5 (int /*long*/[] args) {return IsPreferred (args[0], args[1], args[2]);}
		public int /*long*/ method6 (int /*long*/[] args) {return CanHandleContent (args[0], args[1], args[2], args[3]);}
		public int /*long*/ method7 (int /*long*/[] args) {return GetLoadCookie (args[0]);}
		public int /*long*/ method8 (int /*long*/[] args) {return SetLoadCookie (args[0]);}
		public int /*long*/ method9 (int /*long*/[] args) {return GetParentContentListener (args[0]);}
		public int /*long*/ method10 (int /*long*/[] args) {return SetParentContentListener (args[0]);}		
	};
	
	tooltipListener = new XPCOMObject (new int[] {2, 0, 0, 3, 0}) {
		public int /*long*/ method0 (int /*long*/[] args) {return QueryInterface (args[0], args[1]);}
		public int /*long*/ method1 (int /*long*/[] args) {return AddRef ();}
		public int /*long*/ method2 (int /*long*/[] args) {return Release ();}
		public int /*long*/ method3 (int /*long*/[] args) {return OnShowTooltip (args[0], args[1], args[2]);}
		public int /*long*/ method4 (int /*long*/[] args) {return OnHideTooltip ();}		
	};
}

void disposeCOMInterfaces () {
	if (supports != null) {
		supports.dispose ();
		supports = null;
	}	
	if (weakReference != null) {
		weakReference.dispose ();
		weakReference = null;	
	}
	if (webProgressListener != null) {
		webProgressListener.dispose ();
		webProgressListener = null;
	}
	if (webBrowserChrome != null) {
		webBrowserChrome.dispose ();
		webBrowserChrome = null;
	}
	if (webBrowserChromeFocus != null) {
		webBrowserChromeFocus.dispose ();
		webBrowserChromeFocus = null;
	}
	if (embeddingSiteWindow != null) {
		embeddingSiteWindow.dispose ();
		embeddingSiteWindow = null;
	}
	if (interfaceRequestor != null) {
		interfaceRequestor.dispose ();
		interfaceRequestor = null;
	}		
	if (supportsWeakReference != null) {
		supportsWeakReference.dispose ();
		supportsWeakReference = null;
	}	
	if (contextMenuListener != null) {
		contextMenuListener.dispose ();
		contextMenuListener = null;
	}
	if (uriContentListener != null) {
		uriContentListener.dispose ();
		uriContentListener = null;
	}
	if (tooltipListener != null) {
		tooltipListener.dispose ();
		tooltipListener = null;
	}
}

public boolean execute (String script) {
	String url = "javascript:" + script + ";void(0);";	//$NON-NLS-1$ //$NON-NLS-2$
	int /*long*/[] result = new int /*long*/[1];
	int rc = webBrowser.QueryInterface (nsIWebNavigation.NS_IWEBNAVIGATION_IID, result);
	if (rc != XPCOM.NS_OK) error (rc);
	if (result[0] == 0) error (XPCOM.NS_ERROR_NO_INTERFACE);

	nsIWebNavigation webNavigation = new nsIWebNavigation (result[0]);
    char[] arg = url.toCharArray (); 
    char[] c = new char[arg.length+1];
    System.arraycopy (arg, 0, c, 0, arg.length);
	rc = webNavigation.LoadURI (c, nsIWebNavigation.LOAD_FLAGS_NONE, 0, 0, 0);
	webNavigation.Release ();
	return rc == XPCOM.NS_OK;
}

static Browser findBrowser (int /*long*/ handle) {
	return MozillaDelegate.findBrowser (handle);
}

public boolean forward () {
	int /*long*/[] result = new int /*long*/[1];
	int rc = webBrowser.QueryInterface (nsIWebNavigation.NS_IWEBNAVIGATION_IID, result);
	if (rc != XPCOM.NS_OK) error (rc);
	if (result[0] == 0) error (XPCOM.NS_ERROR_NO_INTERFACE);
	
	nsIWebNavigation webNavigation = new nsIWebNavigation (result[0]);
	rc = webNavigation.GoForward ();
	webNavigation.Release ();

	return rc == XPCOM.NS_OK;
}

public String getUrl () {
	int /*long*/[] result = new int /*long*/[1];
	int rc = webBrowser.QueryInterface (nsIWebNavigation.NS_IWEBNAVIGATION_IID, result);
	if (rc != XPCOM.NS_OK) error (rc);
	if (result[0] == 0) error (XPCOM.NS_ERROR_NO_INTERFACE);

	nsIWebNavigation webNavigation = new nsIWebNavigation (result[0]);
	int /*long*/[] aCurrentURI = new int /*long*/[1];
	rc = webNavigation.GetCurrentURI (aCurrentURI);
	if (rc != XPCOM.NS_OK) error (rc);
	webNavigation.Release ();

	byte[] dest = null;
	if (aCurrentURI[0] != 0) {
		nsIURI uri = new nsIURI (aCurrentURI[0]);
		int /*long*/ aSpec = XPCOM.nsEmbedCString_new ();
		rc = uri.GetSpec (aSpec);
		if (rc != XPCOM.NS_OK) error (rc);
		int length = XPCOM.nsEmbedCString_Length (aSpec);
		int /*long*/ buffer = XPCOM.nsEmbedCString_get (aSpec);
		dest = new byte[length];
		XPCOM.memmove (dest, buffer, length);
		XPCOM.nsEmbedCString_delete (aSpec);
		uri.Release ();
	}
	if (dest == null) return ""; //$NON-NLS-1$
	/*
	 * If the URI indicates that the current page is being rendered from
	 * memory (ie.- via setText()) then answer about:blank as the URL
	 * to be consistent with win32.
	 */
	String location = new String (dest);
	if (location.equals (URI_FROMMEMORY)) location = ABOUT_BLANK;
	return location;
}

public Object getWebBrowser () {
	if ((browser.getStyle () & SWT.MOZILLA) == 0) return null;
	if (webBrowserObject != null) return webBrowserObject;

	try {
		Class clazz = Class.forName ("org.mozilla.xpcom.Mozilla"); //$NON-NLS-1$
		Method method = clazz.getMethod ("getInstance", new Class[0]); //$NON-NLS-1$
		Object mozilla = method.invoke (null, new Object[0]);
		method = clazz.getMethod ("wrapXPCOMObject", new Class[] {Long.TYPE, Class.forName ("java.lang.String")}); //$NON-NLS-1$ //$NON-NLS-2$
		webBrowserObject = method.invoke (mozilla, new Object[] {new Long (webBrowser.getAddress ()), nsIWebBrowser.NS_IWEBBROWSER_IID_STR});
		return webBrowserObject;
	} catch (ClassNotFoundException e) {
	} catch (NoSuchMethodException e) {
	} catch (IllegalArgumentException e) {
	} catch (IllegalAccessException e) {
	} catch (InvocationTargetException e) {
	}
	return null;
}

public boolean isBackEnabled () {
	int /*long*/[] result = new int /*long*/[1];
	int rc = webBrowser.QueryInterface (nsIWebNavigation.NS_IWEBNAVIGATION_IID, result);
	if (rc != XPCOM.NS_OK) error (rc);
	if (result[0] == 0) error (XPCOM.NS_ERROR_NO_INTERFACE);
	
	nsIWebNavigation webNavigation = new nsIWebNavigation (result[0]);
	boolean[] aCanGoBack = new boolean[1];
	rc = webNavigation.GetCanGoBack (aCanGoBack);	
	webNavigation.Release ();
	return aCanGoBack[0];
}

public boolean isForwardEnabled () {
	int /*long*/[] result = new int /*long*/[1];
	int rc = webBrowser.QueryInterface (nsIWebNavigation.NS_IWEBNAVIGATION_IID, result);
	if (rc != XPCOM.NS_OK) error (rc);
	if (result[0] == 0) error (XPCOM.NS_ERROR_NO_INTERFACE);
	
	nsIWebNavigation webNavigation = new nsIWebNavigation (result[0]);
	boolean[] aCanGoForward = new boolean[1];
	rc = webNavigation.GetCanGoForward (aCanGoForward);
	webNavigation.Release ();
	return aCanGoForward[0];
}

static String error (int code) {
	throw new SWTError ("XPCOM error " + code); //$NON-NLS-1$
}

void onDispose (Display display) {
	int rc = webBrowser.RemoveWebBrowserListener (weakReference.getAddress (), nsIWebProgressListener.NS_IWEBPROGRESSLISTENER_IID);
	if (rc != XPCOM.NS_OK) error (rc);

	rc = webBrowser.SetParentURIContentListener (0);
	if (rc != XPCOM.NS_OK) error (rc);
	
	int /*long*/[] result = new int /*long*/[1];
	rc = webBrowser.QueryInterface (nsIBaseWindow.NS_IBASEWINDOW_IID, result);
	if (rc != XPCOM.NS_OK) error (rc);
	if (result[0] == 0) error (XPCOM.NS_ERROR_NO_INTERFACE);
	
	nsIBaseWindow baseWindow = new nsIBaseWindow (result[0]);
	rc = baseWindow.Destroy ();
	if (rc != XPCOM.NS_OK) error (rc);
	baseWindow.Release ();
	
	Release ();
	webBrowser.Release ();
	webBrowser = null;
	webBrowserObject = null;

	if (tip != null && !tip.isDisposed ()) tip.dispose ();
	tip = null;
	location = size = null;

	delegate.onDispose (embedHandle);
	delegate = null;

	embedHandle = 0;
	BrowserCount--;
}

void Activate () {
	int /*long*/[] result = new int /*long*/[1];
	int rc = webBrowser.QueryInterface (nsIWebBrowserFocus.NS_IWEBBROWSERFOCUS_IID, result);
	if (rc != XPCOM.NS_OK) error (rc);
	if (result[0] == 0) error (XPCOM.NS_ERROR_NO_INTERFACE);
	
	nsIWebBrowserFocus webBrowserFocus = new nsIWebBrowserFocus (result[0]);
	rc = webBrowserFocus.Activate ();
	if (rc != XPCOM.NS_OK) error (rc);
	webBrowserFocus.Release ();
}
	
void Deactivate () {
	int /*long*/[] result = new int /*long*/[1];
	int rc = webBrowser.QueryInterface (nsIWebBrowserFocus.NS_IWEBBROWSERFOCUS_IID, result);
	if (rc != XPCOM.NS_OK) error (rc);
	if (result[0] == 0) error (XPCOM.NS_ERROR_NO_INTERFACE);
	
	nsIWebBrowserFocus webBrowserFocus = new nsIWebBrowserFocus (result[0]);
	rc = webBrowserFocus.Deactivate ();
	if (rc != XPCOM.NS_OK) error (rc);
	webBrowserFocus.Release ();
}

void onResize () {
	Rectangle rect = browser.getClientArea ();
	int width = Math.max (1, rect.width);
	int height = Math.max (1, rect.height);

	int /*long*/[] result = new int /*long*/[1];
	int rc = webBrowser.QueryInterface (nsIBaseWindow.NS_IBASEWINDOW_IID, result);
	if (rc != XPCOM.NS_OK) error (rc);
	if (result[0] == 0) error (XPCOM.NS_ERROR_NO_INTERFACE);

	delegate.setSize (embedHandle, width, height);
	nsIBaseWindow baseWindow = new nsIBaseWindow (result[0]);
	rc = baseWindow.SetPositionAndSize (0, 0, width, height, true);
	if (rc != XPCOM.NS_OK) error (rc);
	baseWindow.Release ();
}

public void refresh () {
	int /*long*/[] result = new int /*long*/[1];
	int rc = webBrowser.QueryInterface (nsIWebNavigation.NS_IWEBNAVIGATION_IID, result);
	if (rc != XPCOM.NS_OK) error(rc);
	if (result[0] == 0) error (XPCOM.NS_ERROR_NO_INTERFACE);
	
	nsIWebNavigation webNavigation = new nsIWebNavigation (result[0]);		 	
	rc = webNavigation.Reload (nsIWebNavigation.LOAD_FLAGS_NONE);
	webNavigation.Release ();
	if (rc == XPCOM.NS_OK) return;
	/*
	* Feature in Mozilla.  Reload returns an error code NS_ERROR_INVALID_POINTER
	* when it is called immediately after a request to load a new document using
	* LoadURI.  The workaround is to ignore this error code.
	*
	* Feature in Mozilla.  Attempting to reload a file that no longer exists
	* returns an error code of NS_ERROR_FILE_NOT_FOUND.  This is equivalent to
	* attempting to load a non-existent local url, which is not a Browser error,
	* so this error code should be ignored. 
	*/
	if (rc != XPCOM.NS_ERROR_INVALID_POINTER && rc != XPCOM.NS_ERROR_FILE_NOT_FOUND) error (rc);
}

public boolean setText (String html) {
	/*
	*  Feature in Mozilla.  The focus memory of Mozilla must be 
	*  properly managed through the nsIWebBrowserFocus interface.
	*  In particular, nsIWebBrowserFocus.deactivate must be called
	*  when the focus moves from the browser (or one of its children
	*  managed by Mozilla to another widget.  We currently do not
	*  get notified when a widget takes focus away from the Browser.
	*  As a result, deactivate is not properly called. This causes
	*  Mozilla to retake focus the next time a document is loaded.
	*  This breaks the case where the HTML loaded in the Browser 
	*  varies while the user enters characters in a text widget. The text
	*  widget loses focus every time new content is loaded.
	*  The current workaround is to call deactivate everytime if 
	*  the browser currently does not have focus. A better workaround
	*  would be to have a way to call deactivate when the Browser
	*  or one of its children loses focus.
	*/
	if (browser != browser.getDisplay ().getFocusControl ()) Deactivate ();
	
	/*
	 * Convert the String containing HTML to an array of
	 * bytes with UTF-8 data.
	 */
	byte[] data = null;
	try {
		data = html.getBytes ("UTF-8"); //$NON-NLS-1$
	} catch (UnsupportedEncodingException e) {
		return false;
	}

	int /*long*/[] result = new int /*long*/[1];
	int rc = XPCOM.NS_GetServiceManager (result);
	if (rc != XPCOM.NS_OK) error (rc);
	if (result[0] == 0) error (XPCOM.NS_NOINTERFACE);

	nsIServiceManager serviceManager = new nsIServiceManager (result[0]);
	result[0] = 0;
	rc = serviceManager.GetService (XPCOM.NS_IOSERVICE_CID, nsIIOService.NS_IIOSERVICE_IID, result);
	if (rc != XPCOM.NS_OK) error (rc);
	if (result[0] == 0) error (XPCOM.NS_NOINTERFACE);
	serviceManager.Release ();

	nsIIOService ioService = new nsIIOService (result[0]);
	result[0] = 0;
	/*
	* Note.  Mozilla ignores LINK tags used to load CSS stylesheets
	* when the URI protocol for the nsInputStreamChannel
	* is about:blank.  The fix is to specify the file protocol.
	*/
	byte[] aString = MozillaDelegate.wcsToMbcs (null, URI_FROMMEMORY, false);
	int /*long*/ aSpec = XPCOM.nsEmbedCString_new (aString, aString.length);
	rc = ioService.NewURI (aSpec, null, 0, result);
	XPCOM.nsEmbedCString_delete (aSpec);
	if (rc != XPCOM.NS_OK) error (rc);
	if (result[0] == 0) error (XPCOM.NS_NOINTERFACE);
	ioService.Release ();
	
	nsIURI uri = new nsIURI (result[0]);
	result[0] = 0;

	/* aContentType */
	byte[] contentTypeBuffer = MozillaDelegate.wcsToMbcs (null, "text/html", true); // $NON-NLS-1$
	int /*long*/ aContentType = XPCOM.nsEmbedCString_new (contentTypeBuffer, contentTypeBuffer.length);

	/*
	 * First try to use nsIWebBrowserStream to set the text into the Browser, since this
	 * interface is frozen.  However, this may fail because this interface was only introduced
	 * as of mozilla 1.8; if this interface is not found then use the pre-1.8 approach of
	 * utilizing nsIDocShell instead. 
	 */
	result[0] = 0;
	rc = webBrowser.QueryInterface (nsIWebBrowserStream.NS_IWEBBROWSERSTREAM_IID, result);
	if (rc == XPCOM.NS_OK) {
		if (result[0] == 0) error (XPCOM.NS_ERROR_NO_INTERFACE);
		nsIWebBrowserStream stream = new nsIWebBrowserStream (result[0]);
		rc = stream.OpenStream (uri.getAddress (), aContentType);
		if (rc != XPCOM.NS_OK) error (rc);
		int /*long*/ ptr = C.malloc (data.length);
		XPCOM.memmove (ptr, data, data.length);
		int pageSize = 8192;
		int pageCount = data.length / pageSize + 1;
		int /*long*/ current = ptr;
		for (int i = 0; i < pageCount; i++) {
			int length = i == pageCount - 1 ? data.length % pageSize : pageSize;
			if (length > 0) {
				rc = stream.AppendToStream (current, length);
				if (rc != XPCOM.NS_OK) error (rc);
			}
			current += pageSize;
		}
		rc = stream.CloseStream ();
		if (rc != XPCOM.NS_OK) error (rc);
		C.free (ptr);
		stream.Release ();
	} else {
		rc = webBrowser.QueryInterface (nsIInterfaceRequestor.NS_IINTERFACEREQUESTOR_IID, result);
		if (rc != XPCOM.NS_OK) error (rc);
		if (result[0] == 0) error (XPCOM.NS_ERROR_NO_INTERFACE);

		nsIInterfaceRequestor interfaceRequestor = new nsIInterfaceRequestor (result[0]);
		result[0] = 0;
		rc = interfaceRequestor.GetInterface (nsIDocShell.NS_IDOCSHELL_IID, result);				
		interfaceRequestor.Release ();

		nsIDocShell docShell = new nsIDocShell (result[0]);
		result[0] = 0;
		byte[] contentCharsetBuffer = MozillaDelegate.wcsToMbcs (null, "UTF-8", true);	//$NON-NLS-1$
		int /*long*/ aContentCharset = XPCOM.nsEmbedCString_new (contentCharsetBuffer, contentCharsetBuffer.length);

		/*
		* Feature in Mozilla. LoadStream invokes the nsIInputStream argument
		* through a different thread.  The callback mechanism must attach 
		* a non java thread to the JVM otherwise the nsIInputStream Read and
		* Close methods never get called.
		*/
		InputStream inputStream = new InputStream (data);
		inputStream.AddRef ();
		rc = docShell.LoadStream(inputStream.getAddress (), uri.getAddress (), aContentType,  aContentCharset, 0);
		if (rc != XPCOM.NS_OK) error (rc);
		XPCOM.nsEmbedCString_delete (aContentCharset);
		inputStream.Release ();
		docShell.Release ();
	}

	XPCOM.nsEmbedCString_delete (aContentType);
	uri.Release ();
	return true;
}

public boolean setUrl (String url) {
	int /*long*/[] result = new int /*long*/[1];
	int rc = webBrowser.QueryInterface (nsIWebNavigation.NS_IWEBNAVIGATION_IID, result);
	if (rc != XPCOM.NS_OK) error (rc);
	if (result[0] == 0) error (XPCOM.NS_ERROR_NO_INTERFACE);

	nsIWebNavigation webNavigation = new nsIWebNavigation (result[0]);
    char[] arg = url.toCharArray (); 
    char[] c = new char[arg.length + 1];
    System.arraycopy (arg, 0, c, 0, arg.length);
	rc = webNavigation.LoadURI (c, nsIWebNavigation.LOAD_FLAGS_NONE, 0, 0, 0);
	webNavigation.Release ();
	return rc == XPCOM.NS_OK;
}

public void stop () {
	int /*long*/[] result = new int /*long*/[1];
	int rc = webBrowser.QueryInterface (nsIWebNavigation.NS_IWEBNAVIGATION_IID, result);
	if (rc != XPCOM.NS_OK) error (rc);
	if (result[0] == 0) error (XPCOM.NS_ERROR_NO_INTERFACE);
	
	nsIWebNavigation webNavigation = new nsIWebNavigation (result[0]);	 	
	rc = webNavigation.Stop (nsIWebNavigation.STOP_ALL);
	if (rc != XPCOM.NS_OK) error (rc);
	webNavigation.Release ();
}

/* nsISupports */

int /*long*/ QueryInterface (int /*long*/ riid, int /*long*/ ppvObject) {
	if (riid == 0 || ppvObject == 0) return XPCOM.NS_ERROR_NO_INTERFACE;

	nsID guid = new nsID ();
	XPCOM.memmove (guid, riid, nsID.sizeof);

	if (guid.Equals (nsISupports.NS_ISUPPORTS_IID)) {
		XPCOM.memmove (ppvObject, new int /*long*/[] {supports.getAddress ()}, C.PTR_SIZEOF);
		AddRef ();
		return XPCOM.NS_OK;
	}
	if (guid.Equals (nsIWeakReference.NS_IWEAKREFERENCE_IID)) {
		XPCOM.memmove (ppvObject, new int /*long*/[] {weakReference.getAddress ()}, C.PTR_SIZEOF);
		AddRef ();
		return XPCOM.NS_OK;
	}
	if (guid.Equals (nsIWebProgressListener.NS_IWEBPROGRESSLISTENER_IID)) {
		XPCOM.memmove (ppvObject, new int /*long*/[] {webProgressListener.getAddress ()}, C.PTR_SIZEOF);
		AddRef ();
		return XPCOM.NS_OK;
	}
	if (guid.Equals (nsIWebBrowserChrome.NS_IWEBBROWSERCHROME_IID)) {
		XPCOM.memmove (ppvObject, new int /*long*/[] {webBrowserChrome.getAddress ()}, C.PTR_SIZEOF);
		AddRef ();
		return XPCOM.NS_OK;
	}
	if (guid.Equals (nsIWebBrowserChromeFocus.NS_IWEBBROWSERCHROMEFOCUS_IID)) {
		XPCOM.memmove (ppvObject, new int /*long*/[] {webBrowserChromeFocus.getAddress ()}, C.PTR_SIZEOF);
		AddRef ();
		return XPCOM.NS_OK;
	}
	if (guid.Equals (nsIEmbeddingSiteWindow.NS_IEMBEDDINGSITEWINDOW_IID)) {
		XPCOM.memmove (ppvObject, new int /*long*/[] {embeddingSiteWindow.getAddress ()}, C.PTR_SIZEOF);
		AddRef ();
		return XPCOM.NS_OK;
	}
	if (guid.Equals (nsIInterfaceRequestor.NS_IINTERFACEREQUESTOR_IID)) {
		XPCOM.memmove (ppvObject, new int /*long*/[] {interfaceRequestor.getAddress ()}, C.PTR_SIZEOF);
		AddRef ();
		return XPCOM.NS_OK;
	}
	if (guid.Equals (nsISupportsWeakReference.NS_ISUPPORTSWEAKREFERENCE_IID)) {
		XPCOM.memmove (ppvObject, new int /*long*/[] {supportsWeakReference.getAddress ()}, C.PTR_SIZEOF);
		AddRef ();
		return XPCOM.NS_OK;
	}
	if (guid.Equals (nsIContextMenuListener.NS_ICONTEXTMENULISTENER_IID)) {
		XPCOM.memmove (ppvObject, new int /*long*/[] {contextMenuListener.getAddress ()}, C.PTR_SIZEOF);
		AddRef ();
		return XPCOM.NS_OK;
	}
	if (guid.Equals (nsIURIContentListener.NS_IURICONTENTLISTENER_IID)) {
		XPCOM.memmove (ppvObject, new int /*long*/[] {uriContentListener.getAddress ()}, C.PTR_SIZEOF);
		AddRef ();
		return XPCOM.NS_OK;
	}
	if (guid.Equals (nsITooltipListener.NS_ITOOLTIPLISTENER_IID)) {
		XPCOM.memmove (ppvObject, new int /*long*/[] {tooltipListener.getAddress ()}, C.PTR_SIZEOF);
		AddRef ();
		return XPCOM.NS_OK;
	}
	XPCOM.memmove (ppvObject, new int /*long*/[] {0}, C.PTR_SIZEOF);
	return XPCOM.NS_ERROR_NO_INTERFACE;
}

int /*long*/ AddRef () {
	refCount++;
	return refCount;
}

int /*long*/ Release () {
	refCount--;
	if (refCount == 0) disposeCOMInterfaces ();
	return refCount;
}

/* nsIWeakReference */	
	
int /*long*/ QueryReferent (int /*long*/ riid, int /*long*/ ppvObject) {
	return QueryInterface (riid, ppvObject);
}

/* nsIInterfaceRequestor */

int /*long*/ GetInterface (int /*long*/ riid, int /*long*/ ppvObject) {
	if (riid == 0 || ppvObject == 0) return XPCOM.NS_ERROR_NO_INTERFACE;
	nsID guid = new nsID ();
	XPCOM.memmove (guid, riid, nsID.sizeof);
	if (guid.Equals (nsIDOMWindow.NS_IDOMWINDOW_IID)) {
		int /*long*/[] aContentDOMWindow = new int /*long*/[1];
		int rc = webBrowser.GetContentDOMWindow (aContentDOMWindow);
		if (rc != XPCOM.NS_OK) error (rc);
		if (aContentDOMWindow[0] == 0) error (XPCOM.NS_ERROR_NO_INTERFACE);
		XPCOM.memmove (ppvObject, aContentDOMWindow, C.PTR_SIZEOF);
		return rc;
	}
	return QueryInterface (riid, ppvObject);
}

int /*long*/ GetWeakReference (int /*long*/ ppvObject) {
	XPCOM.memmove (ppvObject, new int /*long*/[] {weakReference.getAddress ()}, C.PTR_SIZEOF);
	AddRef ();
	return XPCOM.NS_OK;
}

/* nsIWebProgressListener */

int /*long*/ OnStateChange (int /*long*/ aWebProgress, int /*long*/ aRequest, int /*long*/ aStateFlags, int /*long*/ aStatus) {
	if ((aStateFlags & nsIWebProgressListener.STATE_IS_DOCUMENT) == 0) return XPCOM.NS_OK;
	if ((aStateFlags & nsIWebProgressListener.STATE_START) != 0) {
		if (request == 0) request = aRequest;
	} else if ((aStateFlags & nsIWebProgressListener.STATE_REDIRECTING) != 0) {
		if (request == aRequest) request = 0;
	} else if ((aStateFlags & nsIWebProgressListener.STATE_STOP) != 0) {
		/*
		* Feature on Mozilla.  When a request is redirected (STATE_REDIRECTING),
		* it never reaches the state STATE_STOP and it is replaced with a new request.
		* The new request is received when it is in the state STATE_STOP.
		* To handle this case,  the variable request is set to 0 when the corresponding
		* request is redirected. The following request received with the state STATE_STOP
		* - the new request resulting from the redirection - is used to send
		* the ProgressListener.completed event.
		*/
		if (request == aRequest || request == 0) {
			request = 0;
			StatusTextEvent event = new StatusTextEvent (browser);
			event.display = browser.getDisplay ();
			event.widget = browser;
			event.text = ""; //$NON-NLS-1$
			for (int i = 0; i < statusTextListeners.length; i++) {
				statusTextListeners[i].changed (event);
			}
			ProgressEvent event2 = new ProgressEvent (browser);
			event2.display = browser.getDisplay ();
			event2.widget = browser;
			for (int i = 0; i < progressListeners.length; i++) {
				progressListeners[i].completed (event2);
			}
		}
	}
	return XPCOM.NS_OK;
}	

int /*long*/ OnProgressChange (int /*long*/ aWebProgress, int /*long*/ aRequest, int /*long*/ aCurSelfProgress, int /*long*/ aMaxSelfProgress, int /*long*/ aCurTotalProgress, int /*long*/ aMaxTotalProgress) {
	if (progressListeners.length == 0) return XPCOM.NS_OK;
	ProgressEvent event = new ProgressEvent (browser);
	event.display = browser.getDisplay ();
	event.widget = browser;
	event.current = (int)/*64*/aCurTotalProgress;
	event.total = (int)/*64*/aMaxTotalProgress;
	for (int i = 0; i < progressListeners.length; i++) {
		progressListeners[i].changed (event);
	}
	return XPCOM.NS_OK;
}

int /*long*/ OnLocationChange (int /*long*/ aWebProgress, int /*long*/ aRequest, int /*long*/ aLocation) {
	/*
	* Feature on Mozilla.  When a page is loaded via setText before a previous
	* setText page load has completed, the expected OnStateChange STATE_STOP for the
	* original setText never arrives because it gets replaced by the OnStateChange
	* STATE_STOP for the new request.  This results in the request field never being
	* cleared because the original request's OnStateChange STATE_STOP is still expected
	* (but never arrives).  To handle this case, the request field is updated to the new
	* overriding request since its OnStateChange STATE_STOP will be received next.
	*/
	if (request != 0 && request != aRequest) request = aRequest;

	if (locationListeners.length == 0) return XPCOM.NS_OK;

	nsIWebProgress webProgress = new nsIWebProgress (aWebProgress);
	int /*long*/[] aDOMWindow = new int /*long*/[1];
	int rc = webProgress.GetDOMWindow (aDOMWindow);
	if (rc != XPCOM.NS_OK) error (rc);
	if (aDOMWindow[0] == 0) error (XPCOM.NS_ERROR_NO_INTERFACE);
	
	nsIDOMWindow domWindow = new nsIDOMWindow (aDOMWindow[0]);
	int /*long*/[] aTop = new int /*long*/[1];
	rc = domWindow.GetTop (aTop);
	if (rc != XPCOM.NS_OK) error (rc);
	if (aTop[0] == 0) error (XPCOM.NS_ERROR_NO_INTERFACE);
	domWindow.Release ();
	
	nsIDOMWindow topWindow = new nsIDOMWindow (aTop[0]);
	topWindow.Release ();
	
	nsIURI location = new nsIURI (aLocation);
	int /*long*/ aSpec = XPCOM.nsEmbedCString_new ();
	location.GetSpec (aSpec);
	int length = XPCOM.nsEmbedCString_Length (aSpec);
	int /*long*/ buffer = XPCOM.nsEmbedCString_get (aSpec);
	byte[] dest = new byte[length];
	XPCOM.memmove (dest, buffer, length);
	XPCOM.nsEmbedCString_delete (aSpec);

	LocationEvent event = new LocationEvent (browser);
	event.display = browser.getDisplay ();
	event.widget = browser;
	event.location = new String (dest);
	if (event.location.equals (URI_FROMMEMORY)) {
		/*
		 * If the URI indicates that the page is being rendered from memory
		 * (ie.- via setText()) then set the event location to about:blank
		 * to be consistent with win32.
		 */
		event.location = ABOUT_BLANK;
	}
	event.top = aTop[0] == aDOMWindow[0];
	for (int i = 0; i < locationListeners.length; i++) {
		locationListeners[i].changed (event);
	}
	return XPCOM.NS_OK;
}

int /*long*/ OnStatusChange (int /*long*/ aWebProgress, int /*long*/ aRequest, int /*long*/ aStatus, int /*long*/ aMessage) {
	if (statusTextListeners.length == 0) return XPCOM.NS_OK;
	StatusTextEvent event = new StatusTextEvent (browser);
	event.display = browser.getDisplay ();
	event.widget = browser;
	int length = XPCOM.strlen_PRUnichar (aMessage);
	char[] dest = new char[length];
	XPCOM.memmove (dest, aMessage, length * 2);
	event.text = new String (dest);
	for (int i = 0; i < statusTextListeners.length; i++) {
		statusTextListeners[i].changed (event);
	}
	return XPCOM.NS_OK;
}		

int /*long*/ OnSecurityChange (int /*long*/ aWebProgress, int /*long*/ aRequest, int /*long*/ state) {
	return XPCOM.NS_OK;
}

/* nsIWebBrowserChrome */

int /*long*/ SetStatus (int /*long*/ statusType, int /*long*/ status) {
	StatusTextEvent event = new StatusTextEvent (browser);
	event.display = browser.getDisplay ();
	event.widget = browser;
	int length = XPCOM.strlen_PRUnichar (status);
	char[] dest = new char[length];
	XPCOM.memmove (dest, status, length * 2);
	String string = new String (dest);
	event.text = string;
	for (int i = 0; i < statusTextListeners.length; i++) {
		statusTextListeners[i].changed (event);
	}
	return XPCOM.NS_OK;
}

int /*long*/ GetWebBrowser (int /*long*/ aWebBrowser) {
	int /*long*/[] ret = new int /*long*/[1];	
	if (webBrowser != null) {
		webBrowser.AddRef ();
		ret[0] = webBrowser.getAddress ();	
	}
	XPCOM.memmove (aWebBrowser, ret, C.PTR_SIZEOF);
	return XPCOM.NS_OK;
}

int /*long*/ SetWebBrowser (int /*long*/ aWebBrowser) {
	if (webBrowser != null) webBrowser.Release ();
	webBrowser = aWebBrowser != 0 ? new nsIWebBrowser (aWebBrowser) : null;  				
	return XPCOM.NS_OK;
}
   
int /*long*/ GetChromeFlags (int /*long*/ aChromeFlags) {
	int[] ret = new int[1];
	ret[0] = chromeFlags;
	XPCOM.memmove (aChromeFlags, ret, 4); /* PRUint32 */
	return XPCOM.NS_OK;
}

int /*long*/ SetChromeFlags (int /*long*/ aChromeFlags) {
	chromeFlags = (int)/*64*/aChromeFlags;
	return XPCOM.NS_OK;
}

int /*long*/ DestroyBrowserWindow () {
	WindowEvent newEvent = new WindowEvent (browser);
	newEvent.display = browser.getDisplay ();
	newEvent.widget = browser;
	for (int i = 0; i < closeWindowListeners.length; i++) {
		closeWindowListeners[i].close (newEvent);
	}
	/*
	* Note on Mozilla.  The DestroyBrowserWindow notification cannot be cancelled.
	* The browser widget cannot be used after this notification has been received.
	* The application is advised to close the window hosting the browser widget.
	* The browser widget must be disposed in all cases.
	*/
	browser.dispose ();
	return XPCOM.NS_OK;
}
   	
int /*long*/ SizeBrowserTo (int /*long*/ aCX, int /*long*/ aCY) {
	size = new Point ((int)/*64*/aCX, (int)/*64*/aCY);
	boolean isModal = (chromeFlags & nsIWebBrowserChrome.CHROME_MODAL) != 0;
	if (isModal) {
		Shell shell = browser.getShell ();
		shell.setSize (shell.computeSize (size.x, size.y));
	}
	return XPCOM.NS_OK;
}

int /*long*/ ShowAsModal () {
	int /*long*/[] result = new int /*long*/[1];
	int rc = XPCOM.NS_GetServiceManager (result);
	if (rc != XPCOM.NS_OK) error (rc);
	if (result[0] == 0) error (XPCOM.NS_NOINTERFACE);

	nsIServiceManager serviceManager = new nsIServiceManager (result[0]);
	result[0] = 0;
	byte[] aContractID = MozillaDelegate.wcsToMbcs (null, XPCOM.NS_CONTEXTSTACK_CONTRACTID, true);
	rc = serviceManager.GetServiceByContractID (aContractID, nsIJSContextStack.NS_IJSCONTEXTSTACK_IID, result);
	if (rc != XPCOM.NS_OK) error (rc);
	if (result[0] == 0) error (XPCOM.NS_NOINTERFACE);
	serviceManager.Release ();

	nsIJSContextStack stack = new nsIJSContextStack (result[0]);
	result[0] = 0;
	rc = stack.Push (0);
	if (rc != XPCOM.NS_OK) error (rc);

	Shell shell = browser.getShell ();
	Display display = browser.getDisplay ();
	while (!shell.isDisposed ()) {
		if (!display.readAndDispatch ()) display.sleep ();
	}

	rc = stack.Pop (result);
	if (rc != XPCOM.NS_OK) error (rc);
	stack.Release ();
	return XPCOM.NS_OK;
}

int /*long*/ IsWindowModal (int /*long*/ retval) {
	int result = (chromeFlags & nsIWebBrowserChrome.CHROME_MODAL) != 0 ? 1 : 0;
	XPCOM.memmove (retval, new int[] {result}, 4); /* PRBool */
	return XPCOM.NS_OK;
}
   
int /*long*/ ExitModalEventLoop (int /*long*/ aStatus) {
	return XPCOM.NS_OK;
}

/* nsIEmbeddingSiteWindow */ 

int /*long*/ SetDimensions (int /*long*/ flags, int /*long*/ x, int /*long*/ y, int /*long*/ cx, int /*long*/ cy) {
	if (flags == nsIEmbeddingSiteWindow.DIM_FLAGS_POSITION) location = new Point ((int)/*64*/x, (int)/*64*/y);
	return XPCOM.NS_OK;   	
}	

int /*long*/ GetDimensions (int /*long*/ flags, int /*long*/ x, int /*long*/ y, int /*long*/ cx, int /*long*/ cy) {
	return XPCOM.NS_OK;     	
}	

int /*long*/ SetFocus () {
	int /*long*/[] result = new int /*long*/[1];
	int rc = webBrowser.QueryInterface (nsIBaseWindow.NS_IBASEWINDOW_IID, result);
	if (rc != XPCOM.NS_OK) error (rc);
	if (result[0] == 0) error (XPCOM.NS_ERROR_NO_INTERFACE);
	
	nsIBaseWindow baseWindow = new nsIBaseWindow (result[0]);
	rc = baseWindow.SetFocus ();
	if (rc != XPCOM.NS_OK) error (rc);
	baseWindow.Release ();

	/*
	* Note. Mozilla notifies here that one of the children took
	* focus. This could or should be used to fire an SWT.FOCUS_IN
	* event on Browser focus listeners.
	*/
	return XPCOM.NS_OK;     	
}	

int /*long*/ GetVisibility (int /*long*/ aVisibility) {
	XPCOM.memmove (aVisibility, new int[] {browser.isVisible () ? 1 : 0}, 4); /* PRBool */
	return XPCOM.NS_OK;
}

int /*long*/ SetVisibility (int /*long*/ aVisibility) {
	if (isChild) {
		WindowEvent event = new WindowEvent (browser);
		event.display = browser.getDisplay ();
		event.widget = browser;
		if (aVisibility == 1) {
			/*
			* Bug in Mozilla.  When the JavaScript window.open is executed, Mozilla
			* fires multiple SetVisibility 1 notifications.  The workaround is
			* to ignore subsequent notifications. 
			*/
			if (!visible) {
				visible = true;
				event.location = location;
				event.size = size;
				event.addressBar = (chromeFlags & nsIWebBrowserChrome.CHROME_LOCATIONBAR) != 0;
				event.menuBar = (chromeFlags & nsIWebBrowserChrome.CHROME_MENUBAR) != 0;
				event.statusBar = (chromeFlags & nsIWebBrowserChrome.CHROME_STATUSBAR) != 0;
				event.toolBar = (chromeFlags & nsIWebBrowserChrome.CHROME_TOOLBAR) != 0;
				for (int i = 0; i < visibilityWindowListeners.length; i++) {
					visibilityWindowListeners[i].show (event);
				}
				location = null;
				size = null;
			}
		} else {
			visible = false;
			for (int i = 0; i < visibilityWindowListeners.length; i++) {
				visibilityWindowListeners[i].hide (event);
			}
		}
	} else {
		visible = aVisibility != 0;
	}
	return XPCOM.NS_OK;     	
}

int /*long*/ GetTitle (int /*long*/ aTitle) {
	return XPCOM.NS_OK;     	
}
 
int /*long*/ SetTitle (int /*long*/ aTitle) {
	if (titleListeners.length == 0) return XPCOM.NS_OK;
	TitleEvent event = new TitleEvent (browser);
	event.display = browser.getDisplay ();
	event.widget = browser;
	/*
	* To be consistent with other platforms the title event should
	* contain the page's url if the page does not contain a <title>
	* tag. 
	*/
	int length = XPCOM.strlen_PRUnichar (aTitle);
	if (length > 0) {
		char[] dest = new char[length];
		XPCOM.memmove (dest, aTitle, length * 2);
		event.title = new String (dest);
	} else {
		event.title = getUrl ();
	}
	for (int i = 0; i < titleListeners.length; i++) {
		titleListeners[i].changed (event);
	}
	return XPCOM.NS_OK;     	
}

int /*long*/ GetSiteWindow (int /*long*/ aSiteWindow) {
	/*
	* Note.  The handle is expected to be an HWND on Windows and
	* a GtkWidget* on GTK.  This callback is invoked on Windows
	* when the javascript window.print is invoked and the print
	* dialog comes up. If no handle is returned, the print dialog
	* does not come up on this platform.  
	*/
	XPCOM.memmove (aSiteWindow, new int /*long*/[] {embedHandle}, C.PTR_SIZEOF);
	return XPCOM.NS_OK;     	
}  
 
/* nsIWebBrowserChromeFocus */

int /*long*/ FocusNextElement () {
	/*
	* Bug in Mozilla embedding API.  Mozilla takes back the focus after sending
	* this event.  This prevents tabbing out of Mozilla. This behaviour can be reproduced
	* with the Mozilla application TestGtkEmbed.  The workaround is to
	* send the traversal notification after this callback returns.
	*/
	browser.getDisplay ().asyncExec (new Runnable () {
		public void run () {
			browser.traverse (SWT.TRAVERSE_TAB_NEXT);
		}
	});
	return XPCOM.NS_OK;  
}

int /*long*/ FocusPrevElement () {
	/*
	* Bug in Mozilla embedding API.  Mozilla takes back the focus after sending
	* this event.  This prevents tabbing out of Mozilla. This behaviour can be reproduced
	* with the Mozilla application TestGtkEmbed.  The workaround is to
	* send the traversal notification after this callback returns.
	*/
	browser.getDisplay ().asyncExec (new Runnable () {
		public void run () {
			browser.traverse (SWT.TRAVERSE_TAB_PREVIOUS);
		}
	});
	return XPCOM.NS_OK;     	
}

/* nsIContextMenuListener */

int /*long*/ OnShowContextMenu (int /*long*/ aContextFlags, int /*long*/ aEvent, int /*long*/ aNode) {
	nsIDOMEvent domEvent = new nsIDOMEvent (aEvent);
	int /*long*/[] result = new int /*long*/[1];
	int rc = domEvent.QueryInterface (nsIDOMMouseEvent.NS_IDOMMOUSEEVENT_IID, result);
	if (rc != XPCOM.NS_OK) error (rc);
	if (result[0] == 0) error (XPCOM.NS_NOINTERFACE);

	nsIDOMMouseEvent domMouseEvent = new nsIDOMMouseEvent (result[0]);
	int[] aScreenX = new int[1], aScreenY = new int[1];
	rc = domMouseEvent.GetScreenX (aScreenX);
	if (rc != XPCOM.NS_OK) error (rc);
	rc = domMouseEvent.GetScreenY (aScreenY);
	if (rc != XPCOM.NS_OK) error (rc);
	domMouseEvent.Release ();
	
	Event event = new Event ();
	event.x = aScreenX[0];
	event.y = aScreenY[0];
	browser.notifyListeners (SWT.MenuDetect, event);
	if (!event.doit) return XPCOM.NS_OK;
	Menu menu = browser.getMenu ();
	if (menu != null && !menu.isDisposed ()) {
		if (aScreenX[0] != event.x || aScreenY[0] != event.y) {
			menu.setLocation (event.x, event.y);
		}
		menu.setVisible (true);
	}
	return XPCOM.NS_OK;     	
}

/* nsIURIContentListener */

int /*long*/ OnStartURIOpen (int /*long*/ aURI, int /*long*/ retval) {
	nsIURI location = new nsIURI (aURI);
	int /*long*/ aSpec = XPCOM.nsEmbedCString_new ();
	location.GetSpec (aSpec);
	int length = XPCOM.nsEmbedCString_Length (aSpec);
	int /*long*/ buffer = XPCOM.nsEmbedCString_get (aSpec);
	buffer = XPCOM.nsEmbedCString_get (aSpec);
	byte[] dest = new byte[length];
	XPCOM.memmove (dest, buffer, length);
	XPCOM.nsEmbedCString_delete (aSpec);
	String value = new String (dest);
	if (locationListeners.length == 0) {
		XPCOM.memmove (retval, new int[] {0}, 4); /* PRBool */
		return XPCOM.NS_OK;
	}
	boolean doit = true;
	if (request == 0) {
		LocationEvent event = new LocationEvent (browser);
		event.display = browser.getDisplay();
		event.widget = browser;
		event.location = value;
		if (event.location.equals (URI_FROMMEMORY)) {
			/*
			 * If the URI indicates that the page is being rendered from memory
			 * (ie.- via setText()) then set the event location to about:blank
			 * to be consistent with win32.
			 */
			event.location = ABOUT_BLANK;
		}
		event.doit = doit;
		for (int i = 0; i < locationListeners.length; i++) {
			locationListeners[i].changing (event);
		}
		doit = event.doit;
	}
	XPCOM.memmove (retval, new int[] {doit ? 0 : 1}, 4); /* PRBool */
	return XPCOM.NS_OK;
}

int /*long*/ DoContent (int /*long*/ aContentType, int /*long*/ aIsContentPreferred, int /*long*/ aRequest, int /*long*/ aContentHandler, int /*long*/ retval) {
	return XPCOM.NS_ERROR_NOT_IMPLEMENTED;
}

int /*long*/ IsPreferred (int /*long*/ aContentType, int /*long*/ aDesiredContentType, int /*long*/ retval) {
	boolean preferred = false;
	int size = XPCOM.strlen (aContentType);
	if (size > 0) {
		byte[] typeBytes = new byte[size + 1];
		XPCOM.memmove (typeBytes, aContentType, size);
		String contentType = new String (typeBytes);

		/* do not attempt to handle known problematic content types */
		if (!contentType.equals (XPCOM.CONTENT_MAYBETEXT) && !contentType.equals (XPCOM.CONTENT_MULTIPART)) {
			/* determine whether browser can handle the content type */
			int /*long*/[] result = new int /*long*/[1];
			int rc = XPCOM.NS_GetServiceManager (result);
			if (rc != XPCOM.NS_OK) error (rc);
			if (result[0] == 0) error (XPCOM.NS_NOINTERFACE);
			nsIServiceManager serviceManager = new nsIServiceManager (result[0]);
			result[0] = 0;
			rc = serviceManager.GetService (XPCOM.NS_CATEGORYMANAGER_CID, nsICategoryManager.NS_ICATEGORYMANAGER_IID, result);
			serviceManager.Release ();
			if (rc != XPCOM.NS_OK) error (rc);
			if (result[0] == 0) error (XPCOM.NS_NOINTERFACE);

			nsICategoryManager categoryManager = new nsICategoryManager (result[0]);
			result[0] = 0;
			byte[] categoryBytes = MozillaDelegate.wcsToMbcs (null, "Gecko-Content-Viewers", true);	//$NON-NLS-1$
			rc = categoryManager.GetCategoryEntry (categoryBytes, typeBytes, result);
			categoryManager.Release ();
			/* if no viewer for the content type is registered then rc == XPCOM.NS_ERROR_NOT_AVAILABLE */
			preferred = rc == XPCOM.NS_OK;
		}
	}

	XPCOM.memmove(retval, new int[] {preferred ? 1 : 0}, 4); /* PRBool */
	return XPCOM.NS_OK;
}

int /*long*/ CanHandleContent (int /*long*/ aContentType, int /*long*/ aIsContentPreferred, int /*long*/ aDesiredContentType, int /*long*/ retval) {
	return XPCOM.NS_ERROR_NOT_IMPLEMENTED;
}

int /*long*/ GetLoadCookie (int /*long*/ aLoadCookie) {
	return XPCOM.NS_ERROR_NOT_IMPLEMENTED;
}

int /*long*/ SetLoadCookie (int /*long*/ aLoadCookie) {
	return XPCOM.NS_ERROR_NOT_IMPLEMENTED;
}

int /*long*/ GetParentContentListener (int /*long*/ aParentContentListener) {
	return XPCOM.NS_ERROR_NOT_IMPLEMENTED;
}
	
int /*long*/ SetParentContentListener (int /*long*/ aParentContentListener) {
	return XPCOM.NS_ERROR_NOT_IMPLEMENTED;
}

/* nsITooltipListener */

int /*long*/ OnShowTooltip (int /*long*/ aXCoords, int /*long*/ aYCoords, int /*long*/ aTipText) {
	int length = XPCOM.strlen_PRUnichar (aTipText);
	char[] dest = new char[length];
	XPCOM.memmove (dest, aTipText, length * 2);
	String text = new String (dest);
	if (tip != null && !tip.isDisposed ()) tip.dispose ();
	Display display = browser.getDisplay ();
	Shell parent = browser.getShell ();
	tip = new Shell (parent, SWT.ON_TOP);
	tip.setLayout (new FillLayout());
	Label label = new Label (tip, SWT.CENTER);
	label.setForeground (display.getSystemColor (SWT.COLOR_INFO_FOREGROUND));
	label.setBackground (display.getSystemColor (SWT.COLOR_INFO_BACKGROUND));
	label.setText (text);
	/*
	* Bug in Mozilla embedded API.  Tooltip coordinates are wrong for 
	* elements inside an inline frame (IFrame tag).  The workaround is 
	* to position the tooltip based on the mouse cursor location.
	*/
	Point point = display.getCursorLocation ();
	/* Assuming cursor is 21x21 because this is the size of
	 * the arrow cursor on Windows
	 */ 
	point.y += 21;
	tip.setLocation (point);
	tip.pack ();
	tip.setVisible (true);
	return XPCOM.NS_OK;
}

int /*long*/ OnHideTooltip () {
	if (tip != null && !tip.isDisposed ()) tip.dispose ();
	tip = null;
	return XPCOM.NS_OK;
}

}
