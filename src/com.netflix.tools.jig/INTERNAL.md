# Integrated implementation sources

This module contains the subset of upstream implementation sources used by Jig. Their packages are rooted below `com.netflix.tools.jig.internal` so a linked Jig module can coexist with any versions selected by an application.

The sources were selected from the static class dependency closure and verified by Jig's complete test suite, including Maven settings, relocation, BOM, source-module, and Spring Boot resolution. They retain their upstream copyright and license headers.

Upstream versions:

- Apache Maven Resolver 2.0.18
- Apache Maven 4.0.0-rc-5 model and resolver support
- Apache Maven Settings 3.9.16
- Plexus Interpolation 1.29, Plexus Utils 4.0.2, Plexus XML 4.1.0, and the Maven 3 credential decryption implementation from Plexus Security Dispatcher 4.1.0
- Woodstox 7.1.1 and Stax2 API 4.2.2
- javax.inject 1

Apache Maven, Maven Resolver, Plexus, Woodstox, and javax.inject sources are provided under the Apache License 2.0.

Jig's `RepositorySystemSupplier` copy intentionally omits the Apache HTTP transporter. Jig installs its JDK HTTP client transporter instead. Optional OSGi and XML schema-validation integration was also omitted.
