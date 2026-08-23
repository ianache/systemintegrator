module.exports = {
  displayName: 'bff',
  preset: '../../jest.preset.js',
  testEnvironment: 'node',
  transform: {
    '^.+\\.[tj]s$': ['ts-jest', { tsconfig: '<rootDir>/tsconfig.spec.json' }],
  },
  moduleFileExtensions: ['ts', 'js', 'html'],
  // openid-client and its dependency chain ship ESM only. Jest's CommonJS
  // runtime does not use Node's require(ESM) interop, so these must be
  // transpiled to CJS instead of being skipped like the rest of node_modules.
  // The character class matches both POSIX and Windows path separators.
  transformIgnorePatterns: [
    'node_modules[/\\\\](?!(?:openid-client|oauth4webapi|jose)[/\\\\])',
  ],
  coverageDirectory: '../../coverage/apps/bff',
};
