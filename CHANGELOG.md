### 0.3.0 - 2016-10-05

This is a minor feature release and maintenance release.

Features:

* Introduce support for `gem-path` config option, which will result in having the
  GEM_PATH set for the jruby intepreters.

Maintenance:

* Introduce lein-parent for managing dependency versions
* Fixes to race conditions and timeouts in tests

### 0.2.1

This is a maintenance release.

 * Bump dependencies to JRuby 1.7.26.

### 0.2.0

This is a feature release.

 * [SERVER-584](https://tickets.puppetlabs.com/browse/SERVER-584)
   Introduce a whitelisting capability to improve the handling of environment variables.

### 0.1.0

 * Initial release.
