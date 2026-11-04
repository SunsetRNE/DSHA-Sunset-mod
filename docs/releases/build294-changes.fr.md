# DSHA 0.2.1-rc.5-20261103.1300-dsh0.2.1-rc.5 / build294

## Intégration des changements amont build279–294

- Expérimental : la page d'écran virtuel est refaite autour de quatre états (désactivé, création, en cours, nettoyage en attente) avec une disposition côte à côte en paysage ; la rotation rouvre l'appli d'origine et l'aperçu flottant revient.
- Expérimental : la page de récupération du démarrage explique les causes des échecs et les actions possibles ; l'écran de chargement natif et le panneau d'erreur du chat proposent des commandes d'attente, de diagnostic et de récupération. Les vérifications ADB en arrière-plan cèdent la priorité à la réouverture après déverrouillage. Il s'agit d'une tentative de correction : le blocage initial n'a pas pu être reproduit.
- Expérimental : les vérifications des extensions en arrière-plan ne s'exécutent que lorsque leur page est visible. L'accès direct au Web et le retour par les notifications ou les applications récentes conservent la destination de démarrage, également sélectionnable dans les réglages Web.
- Si le port du pont de l'appareil n'est pas publié ou ne peut pas être lu, la requête échoue au lieu de se rabattre sur le port 3090 et d'envoyer les identifiants de cette installation. L'interface expérimentale `/app/install` transmet les APK du conteneur à l'installateur système, avec confirmation obligatoire de l'utilisateur ; en arrière-plan, une notification permet d'ouvrir l'installation.
- Expérimental : le diagnostic réseau signale les restrictions d'accès, les problèmes de services ou de DNS et les indices de gel des processus. Il n'effectue aucune réparation automatique.
- La sauvegarde en un clic arrête les écritures avant de vérifier l'emplacement des données et exclut les fichiers d'état propres à l'appareil. Le remplacement de données utilisateur interrompt toujours la sauvegarde.
- Les fenêtres d'information expérimentales s'adaptent à la forme de la fenêtre. Les animations des étapes respectent les réglages système ; l'entrée des appareils compatibles sur la page de l'îlot évite la capsule inférieure ; le défilement du journal de démarrage conserve la même zone de prise en charge jusqu'à la fin du geste.

## Ajustements locaux du démarrage et de l'accueil

- Réduction des vérifications répétées et des lectures disque au démarrage, tout en conservant les contrôles d'intégrité de l'environnement et le traitement des échecs.
- L'événement indiquant que le service est prêt actualise immédiatement l'accueil et poursuit l'entrée dans le Web sans attendre l'actualisation périodique suivante. Les contrôles de disponibilité sont conservés.
- Sur l'accueil, Redémarrer se trouve désormais à gauche et Arrêter à droite, dans les dispositions larges comme étroites. Les langues écrites de droite à gauche conservent le sens de disposition du système.
- Les corrections de fréquence de rafraîchissement élevée, d'appui sur les écrans animés et de gestion des fichiers de l'environnement sont conservées. L'allègement de l'APK reste en place : les environnements normal et de secours partagent les octets d'une archive uniquement si leurs empreintes correspondent ; leurs répertoires d'exécution restent indépendants.

## Version et portée de la vérification

- versionCode est 294 ; la révision DSHA et le DSH intégré sont tous deux en 0.2.1-rc.5. La recette sur appareil reste suspendue et aucun temps de démarrage réel n'a été mesuré pour cette révision. Aucun gain de vitesse fixe n'est promis. Le rapport de livraison consigne les contrôles logiciels de cette révision ; les anciennes validations amont ne constituent pas une validation sur appareil de cette livraison.
