# DSHA 0.2.2-rc.5-20261119.0600-dsh0.2.2-rc.5 / build371

- A aplicação e o DSH incluído estão na versão 0.2.2-rc.5. A compilação local Standard usa o pacote `dsh.android.client` e a assinatura E7E3 existente. É compatível com dispositivos arm64 com Android 11 ou posterior.
- Corrigida a criação de sessões do terminal quando a lista de argumentos predefinidos está vazia.
- Se pedir o arranque enquanto estiver em curso uma tarefa do ambiente, a aplicação aguarda pela sua conclusão e prossegue. Há menos operações repetidas de preparação do ambiente e de registo de extensões durante o arranque.
- Reduzidos os redesenhos repetidos do fundo ao deslocar a interface nativa e o processamento repetido ao deslocar páginas Web.
