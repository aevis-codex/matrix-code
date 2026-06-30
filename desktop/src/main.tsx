import { StrictMode } from 'react';
import { createRoot, type Root } from 'react-dom/client';
import App from './App';

type MatrixCodeRootElement = HTMLElement & {
  matrixCodeRoot?: Root;
};

const root = document.getElementById('root') as MatrixCodeRootElement | null;

if (root) {
  root.matrixCodeRoot ??= createRoot(root);
  root.matrixCodeRoot.render(
    <StrictMode>
      <App />
    </StrictMode>
  );
}
