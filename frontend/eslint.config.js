import js from '@eslint/js';
import globals from 'globals';
import tseslint from 'typescript-eslint';
import hooks from 'eslint-plugin-react-hooks';

export default tseslint.config(
  { ignores: ['dist', 'node_modules', 'playwright-report', 'test-results', 'src/generated'] },
  js.configs.recommended,
  ...tseslint.configs.recommended,
  { files: ['scripts/**/*.mjs'], languageOptions: { globals: globals.node } },
  { files: ['browser/**/*.mjs'], languageOptions: { globals: { ...globals.node, ...globals.browser } } },
  { files: ['**/*.{ts,tsx}'], languageOptions: { globals: { ...globals.browser, ...globals.node } }, plugins: { 'react-hooks': hooks }, rules: hooks.configs.recommended.rules },
  {
    files: ['e2e/**/*.ts'], ignores: ['e2e/status-feedback.ts'],
    rules: { 'no-restricted-syntax': ['error', {
      selector: "CallExpression:matches([callee.property.name='getByRole'], [callee.property.value='getByRole']):matches([arguments.0.value='status'], [arguments.0.type='TemplateLiteral'][arguments.0.expressions.length=0][arguments.0.quasis.0.value.cooked='status'])",
      message: 'Use statusFeedback(scope, expected) so success feedback cannot match an unrelated loading status.',
    }] },
  },
);
