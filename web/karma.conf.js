// Karma configuration.
//
// ChromeHeadlessNoSandbox exists because CI runners execute as root inside a
// container, where Chrome's sandbox cannot initialise. Disabling it is safe for
// a test runner executing our own code and nothing else.
module.exports = function (config) {
  config.set({
    basePath: '',
    frameworks: ['jasmine', '@angular-devkit/build-angular'],
    plugins: [
      require('karma-jasmine'),
      require('karma-chrome-launcher'),
      require('karma-jasmine-html-reporter'),
      require('karma-coverage'),
      require('@angular-devkit/build-angular/plugins/karma'),
    ],
    client: {
      jasmine: { random: true },
      clearContext: false,
    },
    coverageReporter: {
      dir: require('path').join(__dirname, './coverage'),
      subdir: '.',
      reporters: [{ type: 'html' }, { type: 'text-summary' }, { type: 'lcovonly' }],
      check: {
        global: {
          // Lower than the Java gate on purpose. Angular components are mostly
          // template, and chasing a high number there produces tests that assert
          // on markup and break on every styling change. The services — where
          // the logic actually is — are tested properly.
          statements: 40,
          branches: 30,
          functions: 35,
          lines: 40,
        },
      },
    },
    reporters: ['progress', 'kjhtml'],
    browsers: ['ChromeHeadless'],
    customLaunchers: {
      ChromeHeadlessNoSandbox: {
        base: 'ChromeHeadless',
        flags: ['--no-sandbox', '--disable-gpu', '--disable-dev-shm-usage'],
      },
    },
    restartOnFileChange: true,
  });
};
