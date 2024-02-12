/**
 * Development configuration.
 *
 * `apiUrl` is relative on purpose. The dev server proxies /api to the gateway
 * and Nginx does the same in a container, so the built bundle never contains a
 * hostname — which is what makes the same artefact deployable to dev, test and
 * prod.
 */
export const environment = {
  production: false,
  apiUrl: '/api/v1',
};
