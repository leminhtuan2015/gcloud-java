/*
 * Copyright 2015 Google Inc. All Rights Reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.google.cloud;

import static com.google.common.base.MoreObjects.firstNonNull;
import static com.google.common.base.Preconditions.checkArgument;
import static java.nio.charset.StandardCharsets.UTF_8;

import com.google.cloud.spi.ServiceRpcFactory;
import com.google.common.collect.Iterables;
import com.google.common.io.Files;

import org.json.JSONException;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.io.BufferedReader;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileNotFoundException;
import java.io.FileReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.ObjectInputStream;
import java.io.Serializable;
import java.lang.reflect.Method;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.Charset;
import java.util.Enumeration;
import java.util.Locale;
import java.util.Objects;
import java.util.ServiceLoader;
import java.util.Set;
import java.util.jar.Attributes;
import java.util.jar.JarFile;
import java.util.jar.Manifest;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Abstract class representing service options.
 *
 * @param <ServiceT> the service subclass
 * @param <ServiceRpcT> the spi-layer class corresponding to the service
 * @param <OptionsT> the {@code ServiceOptions} subclass corresponding to the service
 */
public abstract class ServiceOptions<ServiceT extends Service<OptionsT>, ServiceRpcT,
    OptionsT extends ServiceOptions<ServiceT, ServiceRpcT, OptionsT>> implements Serializable {

  private static final String DEFAULT_HOST = "https://www.googleapis.com";
  private static final String PROJECT_ENV_NAME = "GCLOUD_PROJECT";
  private static final String MANIFEST_ARTIFACT_ID_KEY = "artifactId";
  private static final String MANIFEST_VERSION_KEY = "Implementation-Version";
  private static final String ARTIFACT_ID = "gcloud-java-core";
  private static final String LIBRARY_NAME = "gcloud-java";
  private static final String LIBRARY_VERSION = getLibraryVersion();
  private static final String APPLICATION_NAME =
      LIBRARY_VERSION == null ? LIBRARY_NAME : LIBRARY_NAME + "/" + LIBRARY_VERSION;
  private static final long serialVersionUID = 3049375916337507361L;

  private final String projectId;
  private final String host;
  private final RestorableState<AuthCredentials> authCredentialsState;
  private final RetryParams retryParams;
  private final String serviceRpcFactoryClassName;
  private final String serviceFactoryClassName;
  private final Clock clock;

  private transient AuthCredentials authCredentials;
  private transient ServiceRpcFactory<ServiceRpcT, OptionsT> serviceRpcFactory;
  private transient ServiceFactory<ServiceT, OptionsT> serviceFactory;
  private transient ServiceT service;
  private transient ServiceRpcT rpc;

  /**
   * Builder for {@code ServiceOptions}.
   *
   * @param <ServiceT> the service subclass
   * @param <ServiceRpcT> the spi-layer class corresponding to the service
   * @param <OptionsT> the {@code ServiceOptions} subclass corresponding to the service
   * @param <B> the {@code ServiceOptions} builder
   */
  protected abstract static class Builder<ServiceT extends Service<OptionsT>, ServiceRpcT,
      OptionsT extends ServiceOptions<ServiceT, ServiceRpcT, OptionsT>,
      B extends Builder<ServiceT, ServiceRpcT, OptionsT, B>> {

    private String projectId;
    private String host;
    private AuthCredentials authCredentials;
    private RetryParams retryParams;
    private ServiceFactory<ServiceT, OptionsT> serviceFactory;
    private ServiceRpcFactory<ServiceRpcT, OptionsT> serviceRpcFactory;
    private Clock clock;

    protected Builder() {}

    protected Builder(ServiceOptions<ServiceT, ServiceRpcT, OptionsT> options) {
      projectId = options.projectId;
      host = options.host;
      authCredentials = options.authCredentials;
      retryParams = options.retryParams;
      serviceFactory = options.serviceFactory;
      serviceRpcFactory = options.serviceRpcFactory;
      clock = options.clock;
    }

    protected abstract ServiceOptions<ServiceT, ServiceRpcT, OptionsT> build();

    @SuppressWarnings("unchecked")
    protected B self() {
      return (B) this;
    }

    /**
     * Sets the service factory.
     */
    public B serviceFactory(ServiceFactory<ServiceT, OptionsT> serviceFactory) {
      this.serviceFactory = serviceFactory;
      return self();
    }

    /**
     * Sets the service's clock. The clock is mainly used for testing purpose. {@link Clock} will be
     * replaced by Java8's {@code java.time.Clock}.
     *
     * @param clock the clock to set
     * @return the builder
     */
    public B clock(Clock clock) {
      this.clock = clock;
      return self();
    }

    /**
     * Sets project id.
     *
     * @return the builder
     */
    public B projectId(String projectId) {
      this.projectId = projectId;
      return self();
    }

    /**
     * Sets service host.
     *
     * @return the builder
     */
    public B host(String host) {
      this.host = host;
      return self();
    }

    /**
     * Sets the service authentication credentials.
     *
     * @return the builder
     */
    public B authCredentials(AuthCredentials authCredentials) {
      this.authCredentials = authCredentials;
      return self();
    }

    /**
     * Sets configuration parameters for request retries. If no configuration is set
     * {@link RetryParams#defaultInstance()} is used. To disable retries, supply
     * {@link RetryParams#noRetries()} here.
     *
     * @return the builder
     */
    public B retryParams(RetryParams retryParams) {
      this.retryParams = retryParams;
      return self();
    }

    /**
     * Sets the factory for rpc services.
     *
     * @return the builder
     */
    public B serviceRpcFactory(ServiceRpcFactory<ServiceRpcT, OptionsT> serviceRpcFactory) {
      this.serviceRpcFactory = serviceRpcFactory;
      return self();
    }
  }

  protected ServiceOptions(Class<? extends ServiceFactory<ServiceT, OptionsT>> serviceFactoryClass,
      Class<? extends ServiceRpcFactory<ServiceRpcT, OptionsT>> rpcFactoryClass,
      Builder<ServiceT, ServiceRpcT, OptionsT, ?> builder) {
    projectId = builder.projectId != null ? builder.projectId : defaultProject();
    if (projectIdRequired()) {
      checkArgument(
          projectId != null,
          "A project ID is required for this service but could not be determined from the builder "
          + "or the environment.  Please set a project ID using the builder.");
    }
    host = firstNonNull(builder.host, defaultHost());
    authCredentials =
        builder.authCredentials != null ? builder.authCredentials : defaultAuthCredentials();
    authCredentialsState = authCredentials != null ? authCredentials.capture() : null;
    retryParams = firstNonNull(builder.retryParams, defaultRetryParams());
    serviceFactory = firstNonNull(builder.serviceFactory,
        getFromServiceLoader(serviceFactoryClass, defaultServiceFactory()));
    serviceFactoryClassName = serviceFactory.getClass().getName();
    serviceRpcFactory = firstNonNull(builder.serviceRpcFactory,
        getFromServiceLoader(rpcFactoryClass, defaultRpcFactory()));
    serviceRpcFactoryClassName = serviceRpcFactory.getClass().getName();
    clock = firstNonNull(builder.clock, Clock.defaultClock());
  }

  /**
   * Returns whether a service requires a project ID. This method may be overridden in
   * service-specific Options objects.
   *
   * @return true if a project ID is required to use the service, false if not
   */
  protected boolean projectIdRequired() {
    return true;
  }

  private static AuthCredentials defaultAuthCredentials() {
    // Consider App Engine.
    if (appEngineAppId() != null) {
      try {
        return AuthCredentials.createForAppEngine();
      } catch (Exception ignore) {
        // Maybe not on App Engine
      }
    }

    try {
      return AuthCredentials.createApplicationDefaults();
    } catch (Exception ex) {
      return null;
    }
  }

  protected static String appEngineAppId() {
    return System.getProperty("com.google.appengine.application.id");
  }

  protected String defaultHost() {
    return DEFAULT_HOST;
  }

  protected String defaultProject() {
    String projectId = System.getProperty(PROJECT_ENV_NAME, System.getenv(PROJECT_ENV_NAME));
    if (projectId == null) {
      projectId = appEngineProjectId();
    }
    if (projectId == null) {
      projectId = serviceAccountProjectId();
    }
    return projectId != null ? projectId : googleCloudProjectId();
  }

  private static String activeGoogleCloudConfig(File configDir) {
    String activeGoogleCloudConfig = null;
    try {
      activeGoogleCloudConfig =
          Files.readFirstLine(new File(configDir, "active_config"), Charset.defaultCharset());
    } catch (IOException ex) {
      // ignore
    }
    // if reading active_config failed or the file is empty we try default
    return firstNonNull(activeGoogleCloudConfig, "default");
  }

  protected static String googleCloudProjectId() {
    File configDir;
    if (System.getenv().containsKey("CLOUDSDK_CONFIG")) {
      configDir = new File(System.getenv("CLOUDSDK_CONFIG"));
    } else if (isWindows() && System.getenv().containsKey("APPDATA")) {
      configDir = new File(System.getenv("APPDATA"), "gcloud");
    } else {
      configDir = new File(System.getProperty("user.home"), ".config/gcloud");
    }
    String activeConfig = activeGoogleCloudConfig(configDir);
    FileReader fileReader = null;
    try {
      fileReader = new FileReader(new File(configDir, "configurations/config_" + activeConfig));
    } catch (FileNotFoundException newConfigFileNotFoundEx) {
      try {
        fileReader = new FileReader(new File(configDir, "properties"));
      } catch (FileNotFoundException oldConfigFileNotFoundEx) {
        // ignore
      }
    }
    if (fileReader != null) {
      try (BufferedReader reader = new BufferedReader(fileReader)) {
        String line;
        String section = null;
        Pattern projectPattern = Pattern.compile("^project\\s*=\\s*(.*)$");
        Pattern sectionPattern = Pattern.compile("^\\[(.*)\\]$");
        while ((line = reader.readLine()) != null) {
          if (line.isEmpty() || line.startsWith(";")) {
            continue;
          }
          line = line.trim();
          Matcher matcher = sectionPattern.matcher(line);
          if (matcher.matches()) {
            section = matcher.group(1);
          } else if (section == null || section.equals("core")) {
            matcher = projectPattern.matcher(line);
            if (matcher.matches()) {
              return matcher.group(1);
            }
          }
        }
      } catch (IOException ex) {
        // ignore
      }
    }
    try {
      URL url = new URL("http://metadata/computeMetadata/v1/project/project-id");
      HttpURLConnection connection = (HttpURLConnection) url.openConnection();
      connection.setRequestProperty("X-Google-Metadata-Request", "True");
      InputStream input = connection.getInputStream();
      if (connection.getResponseCode() == 200) {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(input, UTF_8))) {
          return reader.readLine();
        }
      }
    } catch (IOException ignore) {
      // ignore
    }
    // return null if can't determine
    return null;
  }

  private static boolean isWindows() {
    return System.getProperty("os.name").toLowerCase(Locale.ENGLISH).contains("windows");
  }

  protected static String appEngineProjectId() {
    try {
      Class<?> factoryClass =
          Class.forName("com.google.appengine.api.appidentity.AppIdentityServiceFactory");
      Class<?> serviceClass =
          Class.forName("com.google.appengine.api.appidentity.AppIdentityService");
      Method method = factoryClass.getMethod("getAppIdentityService");
      Object appIdentityService = method.invoke(null);
      method = serviceClass.getMethod("getServiceAccountName");
      String serviceAccountName = (String) method.invoke(appIdentityService);
      int indexOfAtSign = serviceAccountName.indexOf('@');
      return serviceAccountName.substring(0, indexOfAtSign);
    } catch (Exception ignore) {
      // return null if can't determine
      return null;
    }
  }

  protected static String serviceAccountProjectId() {
    String project = null;
    String credentialsPath = System.getenv("GOOGLE_APPLICATION_CREDENTIALS");
    if (credentialsPath != null) {
      try (InputStream credentialsStream = new FileInputStream(credentialsPath)) {
        JSONObject json = new JSONObject(new JSONTokener(credentialsStream));
        project = json.getString("project_id");
      } catch (IOException | JSONException ex) {
        // ignore
      }
    }
    return project;
  }

  @SuppressWarnings("unchecked")
  public ServiceT service() {
    if (service == null) {
      service = serviceFactory.create((OptionsT) this);
    }
    return service;
  }

  @SuppressWarnings("unchecked")
  public ServiceRpcT rpc() {
    if (rpc == null) {
      rpc = serviceRpcFactory.create((OptionsT) this);
    }
    return rpc;
  }

  /**
   * Returns the project id. Return value can be null (for services that don't require a project
   * id).
   */
  public String projectId() {
    return projectId;
  }

  /**
   * Returns the service host.
   */
  public String host() {
    return host;
  }

  /**
   * Returns the authentication credentials.
   */
  public AuthCredentials authCredentials() {
    return authCredentials;
  }

  /**
   * Returns configuration parameters for request retries. By default requests are retried:
   * {@link RetryParams#defaultInstance()} is used.
   */
  public RetryParams retryParams() {
    return retryParams;
  }

  /**
   * Returns the service's clock. Default time source uses {@link System#currentTimeMillis()} to get
   * current time.
   */
  public Clock clock() {
    return clock;
  }

  /**
   * Returns the application's name as a string in the format {@code gcloud-java/[version]}.
   */
  public String applicationName() {
    return APPLICATION_NAME;
  }

  /**
   * Returns the library's name, {@code gcloud-java}, as a string.
   */
  public String libraryName() {
    return LIBRARY_NAME;
  }

  /**
   * Returns the library's version as a string.
   */
  public String libraryVersion() {
    return LIBRARY_VERSION;
  }

  protected int baseHashCode() {
    return Objects.hash(projectId, host, authCredentialsState, retryParams, serviceFactoryClassName,
        serviceRpcFactoryClassName, clock);
  }

  protected boolean baseEquals(ServiceOptions<?, ?, ?> other) {
    return Objects.equals(projectId, other.projectId)
        && Objects.equals(host, other.host)
        && Objects.equals(authCredentialsState, other.authCredentialsState)
        && Objects.equals(retryParams, other.retryParams)
        && Objects.equals(serviceFactoryClassName, other.serviceFactoryClassName)
        && Objects.equals(serviceRpcFactoryClassName, other.serviceRpcFactoryClassName)
        && Objects.equals(clock, clock);
  }

  private void readObject(ObjectInputStream input) throws IOException, ClassNotFoundException {
    input.defaultReadObject();
    serviceFactory = newInstance(serviceFactoryClassName);
    serviceRpcFactory = newInstance(serviceRpcFactoryClassName);
    authCredentials = authCredentialsState != null ? authCredentialsState.restore() : null;
  }

  @SuppressWarnings("unchecked")
  static <T> T newInstance(String className) throws IOException, ClassNotFoundException {
    try {
      return (T) Class.forName(className).newInstance();
    } catch (InstantiationException | IllegalAccessException e) {
      throw new IOException(e);
    }
  }

  protected abstract ServiceFactory<ServiceT, OptionsT> defaultServiceFactory();

  protected abstract ServiceRpcFactory<ServiceRpcT, OptionsT> defaultRpcFactory();

  protected abstract Set<String> scopes();

  public abstract <B extends Builder<ServiceT, ServiceRpcT, OptionsT, B>> B toBuilder();

  /**
   * Some services may have different backoff requirements listed in their SLAs. Be sure to override
   * this method in options subclasses when the service's backoff requirement differs from the
   * default parameters listed in {@link RetryParams}.
   */
  protected RetryParams defaultRetryParams() {
    return RetryParams.defaultInstance();
  }

  static <T> T getFromServiceLoader(Class<? extends T> clazz, T defaultInstance) {
    return Iterables.getFirst(ServiceLoader.load(clazz), defaultInstance);
  }

  private static String getLibraryVersion() {
    String version = null;
    try {
      Enumeration<URL> resources =
          ServiceOptions.class.getClassLoader().getResources(JarFile.MANIFEST_NAME);
      while (resources.hasMoreElements() && version == null) {
        Manifest manifest = new Manifest(resources.nextElement().openStream());
        Attributes manifestAttributes = manifest.getMainAttributes();
        String artifactId = manifestAttributes.getValue(MANIFEST_ARTIFACT_ID_KEY);
        if (artifactId != null && artifactId.equals(ARTIFACT_ID)) {
          version = manifestAttributes.getValue(MANIFEST_VERSION_KEY);
        }
      }
    } catch (IOException e) {
      // ignore
    }
    return version;
  }
}
