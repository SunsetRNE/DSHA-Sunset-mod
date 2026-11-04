# DSHA 0.2.1-rc.5-20261103.1300-dsh0.2.1-rc.5 / build294

## Alterações integradas do projeto de origem, build279–294

- Experimental: a página do ecrã virtual foi refeita em torno de quatro estados (desligado, a criar, em execução e a aguardar limpeza), com disposição lado a lado na horizontal; rodar volta a abrir a aplicação original e a pré-visualização flutuante regressou.
- Experimental: a recuperação do arranque explica as causas das falhas e os passos seguintes; o ecrã de carregamento nativo e o painel de erro da conversa incluem opções de espera, diagnóstico e recuperação. As verificações ADB em segundo plano cedem prioridade ao reabrir a aplicação após desbloquear. Trata-se de uma tentativa de correção: não foi possível reproduzir o bloqueio original.
- Experimental: as verificações de extensões em segundo plano só ocorrem enquanto a página de extensões está visível. A entrada direta no Web e o regresso pelas notificações ou aplicações recentes mantêm o destino de arranque, que também pode ser escolhido nas definições Web.
- Se a porta da ponte do dispositivo não estiver publicada ou não puder ser lida, o pedido falha em vez de recorrer à porta 3090 e enviar as credenciais desta instalação. A interface experimental `/app/install` entrega os APK do contentor ao instalador do sistema, continuando a exigir confirmação do utilizador; em segundo plano, abre a instalação através de uma notificação.
- Experimental: o diagnóstico de rede comunica restrições de acesso, problemas de serviços e DNS e indícios de processos congelados. Não faz reparações automáticas.
- A cópia de segurança com um toque interrompe as escritas antes de verificar a localização dos dados e exclui dessa comparação os ficheiros de estado locais do dispositivo. A substituição de dados do utilizador continua a interromper a cópia.
- Os diálogos informativos experimentais adaptam-se ao formato da janela. As animações das etapas respeitam as definições do sistema; a entrada de dispositivos suportados na página da ilha evita a cápsula inferior; o registo de arranque mantém a mesma área responsável pela deslocação até ao fim do gesto.

## Ajustes locais do arranque e da página inicial

- Redução de verificações repetidas e de leituras do disco no arranque, mantendo as verificações de integridade do ambiente e o tratamento de falhas.
- O evento de serviço pronto atualiza imediatamente a página inicial e prossegue a entrada no Web sem esperar pela atualização periódica seguinte. A verificação de prontidão mantém-se.
- A página inicial passa a ter Reiniciar à esquerda e Parar à direita, tanto na disposição larga como na estreita. Os idiomas escritos da direita para a esquerda continuam a seguir a direção definida pelo sistema.
- Mantêm-se as correções de alta taxa de atualização, de toque em ecrãs animados e de gestão dos ficheiros do ambiente. A redução do tamanho do APK é preservada: os ambientes normal e de recuperação só partilham os bytes do arquivo no pacote quando os respetivos resumos coincidem; os diretórios de execução continuam independentes.

## Versão e âmbito da verificação

- O versionCode é 294; a revisão DSHA e o DSH incluído são ambos 0.2.1-rc.5. A aceitação em dispositivo continua suspensa e não foram medidos tempos de arranque num dispositivo real nesta revisão. Não se promete uma melhoria fixa de velocidade. Os resultados das verificações de software constam do relatório desta entrega; as validações anteriores do projeto de origem não equivalem à aceitação desta revisão em dispositivo.
