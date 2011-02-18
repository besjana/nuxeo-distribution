/*
 * (C) Copyright 2011 Nuxeo SA (http://nuxeo.com/) and contributors.
 *
 * All rights reserved. This program and the accompanying materials
 * are made available under the terms of the GNU Lesser General Public License
 * (LGPL) version 2.1 which accompanies this distribution, and is available at
 * http://www.gnu.org/licenses/lgpl.html
 *
 * This library is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the GNU
 * Lesser General Public License for more details.
 *
 * Contributors:
 *     Sun Seng David TAN
 *     Florent Guillaume
 *     Benoit Delbosc
 */
package org.nuxeo.functionaltests;

import static java.util.concurrent.TimeUnit.SECONDS;

import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.net.URL;
import java.util.ArrayList;
import java.util.List;

import org.apache.commons.io.IOUtils;
import org.junit.AfterClass;
import org.junit.BeforeClass;
import org.nuxeo.functionaltests.pages.DocumentBasePage;
import org.nuxeo.functionaltests.pages.LoginPage;
import org.openqa.selenium.NoSuchElementException;
import org.openqa.selenium.Speed;
import org.openqa.selenium.WebDriver;
import org.openqa.selenium.WebElement;
import org.openqa.selenium.firefox.FirefoxDriver;
import org.openqa.selenium.firefox.FirefoxProfile;
import org.openqa.selenium.internal.WrapsElement;
import org.openqa.selenium.support.PageFactory;
import org.openqa.selenium.support.ui.Clock;
import org.openqa.selenium.support.ui.SystemClock;

/**
 * Base functions for all pages.
 */
public abstract class AbstractTest {

    public static final String NUXEO_URL = "http://localhost:8080/nuxeo";

    private static final String FIREBUG_XPI = "firebug-1.6.2-fx.xpi";

    private static final String FIREBUG_VERSION = "1.6.2";

    private static final int LOAD_TIMEOUT_SECONDS = 5;

    private static final int AJAX_TIMEOUT_SECONDS = 5;

    protected static FirefoxDriver driver;

    protected static File tmp_firebug_xpi;

    @BeforeClass
    public static void initDriver() throws Exception {
        FirefoxProfile profile = new FirefoxProfile();

        // Set English as default language
        profile.setPreference("general.useragent.locale", "en");
        profile.setPreference("intl.accept_languages", "en");

        // flag UserAgent as Selenium tester: this is used in Nuxeo
        profile.setPreference("general.useragent.extra.nuxeo",
                "Nuxeo-Selenium-Tester");

        addFireBug(profile);

        driver = new FirefoxDriver(profile);
        // Set speed between user interaction: keyboard and mouse
        // Fast: 0, MEDIUM: 0.5s SLOW: 1s
        driver.manage().setSpeed(Speed.FAST);
    }

    @AfterClass
    public static void quitDriver() throws InterruptedException {
        // Temporary code to take snapshots of the last page
        // TODO: snapshots only test on failure, prefix using the test name
        driver.saveScreenshot(new File("/tmp/screenshot-lastpage.png"));
        Thread.currentThread().sleep(1000);
        driver.saveScreenshot(new File("/tmp/screenshot-lastpage2.png"));

        if (driver != null) {
            driver.close();
            driver = null;
        }
        removeFireBug();
    }

    protected static void addFireBug(FirefoxProfile profile) throws Exception {
        ClassLoader cl = Thread.currentThread().getContextClassLoader();
        URL xpi_url = cl.getResource(FIREBUG_XPI);
        File xpi;
        if (xpi_url.getProtocol().equals("file")) {
            xpi = new File(xpi_url.getPath());
        } else {
            // copy to a file
            InputStream firebug = cl.getResourceAsStream(FIREBUG_XPI);
            if (firebug == null) {
                throw new RuntimeException(FIREBUG_XPI + " resource not found");
            }
            File tmp = File.createTempFile("nxfirebug", null);
            tmp.delete();
            tmp.mkdir();
            xpi = new File(tmp, FIREBUG_XPI);
            FileOutputStream out = new FileOutputStream(xpi);
            IOUtils.copy(firebug, out);
            firebug.close();
            out.close();
            tmp_firebug_xpi = xpi;
        }
        profile.addExtension(xpi);
        // avoid "first run" page
        profile.setPreference("extensions.firebug.currentVersion",
                FIREBUG_VERSION);
    }

    protected static void removeFireBug() {
        if (tmp_firebug_xpi != null) {
            tmp_firebug_xpi.delete();
            tmp_firebug_xpi.getParentFile().delete();
        }
    }

    public static <T> T get(String url, Class<T> pageClassToProxy) {
        driver.get(url);
        return asPage(pageClassToProxy);
    }

    public static <T> T asPage(Class<T> pageClassToProxy) {
        T page = instantiatePage(driver, pageClassToProxy);
        PageFactory.initElements(new VariableElementLocatorFactory(driver,
                AJAX_TIMEOUT_SECONDS), page);
        // check all required WebElements on the page and wait for their loading
        List<String> fieldNames = new ArrayList<String>();
        List<WrapsElement> elements = new ArrayList<WrapsElement>();
        for (Field field : pageClassToProxy.getDeclaredFields()) {
            if (field.getAnnotation(Required.class) != null) {
                try {
                    field.setAccessible(true);
                    fieldNames.add(field.getName());
                    elements.add((WrapsElement) field.get(page));
                } catch (Exception e) {
                    throw new RuntimeException(e);
                }
            }
        }
        Clock clock = new SystemClock();
        long end = clock.laterBy(SECONDS.toMillis(LOAD_TIMEOUT_SECONDS));
        String notLoaded = null;
        while (clock.isNowBefore(end)) {
            notLoaded = anyElementNotLoaded(elements, fieldNames);
            if (notLoaded == null) {
                return page;
            }
            try {
                Thread.sleep(100);
            } catch (InterruptedException e) {
                // ignore
            }
        }
        throw new RuntimeException("Timeout loading page "
                + pageClassToProxy.getSimpleName() + " missing element "
                + notLoaded);
    }

    protected static String anyElementNotLoaded(List<WrapsElement> proxies,
            List<String> fieldNames) {
        for (int i = 0; i < proxies.size(); i++) {
            WrapsElement proxy = proxies.get(i);
            try {
                // method implemented in LocatingElementHandler
                proxy.getWrappedElement();
            } catch (NoSuchElementException e) {
                return fieldNames.get(i);
            }
        }
        return null;
    }

    // private in PageFactory...
    protected static <T> T instantiatePage(WebDriver driver,
            Class<T> pageClassToProxy) {
        try {
            try {
                Constructor<T> constructor = pageClassToProxy.getConstructor(WebDriver.class);
                return constructor.newInstance(driver);
            } catch (NoSuchMethodException e) {
                return pageClassToProxy.newInstance();
            }
        } catch (RuntimeException e) {
            throw e;
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public LoginPage getLoginPage() {
        return get(NUXEO_URL, LoginPage.class);
    }

    /**
     * Login as Administrator
     *
     * @return the Document base page (by default returned by nuxeo dm)
     */
    public DocumentBasePage login() {
        return login("Administrator", "Administrator");
    }

    public DocumentBasePage login(String username, String password) {
        DocumentBasePage documentBasePage = getLoginPage().login(username,
                password, DocumentBasePage.class);
        documentBasePage.checkUserConnected(username);
        return documentBasePage;
    }


}
