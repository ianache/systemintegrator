# Instrucciones del proyecto

## Exploración de conocimiento

Antes de responder preguntas sobre la arquitectura, el contenido del código, la
documentación o las relaciones entre archivos de este proyecto, usar el skill
**graphify** para explorar el conocimiento disponible (especialmente si ya
existe `graphify-out/` en el repo). Graphify construye un grafo de
conocimiento persistente con detección de comunidades y herramientas de
consulta BFS/DFS — es preferible a una exploración manual con grep/glob
cuando la pregunta es sobre "cómo se relaciona X con Y" o "qué cubre este
proyecto" en términos generales.

## graphify

Para cualquier pregunta o análisis sobre el código, la arquitectura, la
documentación o las relaciones entre componentes del proyecto, usar primero
el skill `graphify` y consultar el grafo existente si está disponible. Esta
regla aplica antes de realizar una exploración manual del repositorio.

## Worktrees

Todos los worktrees del proyecto deben crearse siempre dentro de la carpeta
`.worktrees` en la raíz del repositorio. No crear worktrees en otras carpetas
como `.claude/worktrees` ni fuera del repositorio.
