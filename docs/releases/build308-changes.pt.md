# DSHA 0.2.1-rc.5-20261104.1412-dsh0.2.1-rc.5 / build308

- A confirmação de uma operação em um dispositivo externo fixa o alvo selecionado. As etapas ainda não enviadas são recusadas se a solicitação perder a validade, a associação do dispositivo mudar ou o acesso externo for desativado.
- Os comandos de diagnóstico somente leitura de dispositivos externos passam a validar os argumentos. O comando wm só consulta o tamanho e a densidade da tela. O logcat aceita opções de leitura e filtros compatíveis. Argumentos para alterar as configurações da tela, limpar registros, redimensionar buffers ou gravar arquivos são recusados.
