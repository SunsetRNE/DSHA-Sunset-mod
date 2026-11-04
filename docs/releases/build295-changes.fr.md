# DSHA 0.2.1-rc.5-20261103.1500-dsh0.2.1-rc.5 / build295

## Tablettes, longues conversations et réponses en flux

- Compatibilité SSE : traitement correct des blocs d’événements sans data. Le SDK continue d’accepter un JSON complet à EOF ; un JSON corrompu échoue explicitement.
- Les longues conversations évitent les conversions et créations répétées de composants. Les états de session sont mis à jour progressivement ; l’ordre des branches est conservé sans récursion profonde.
- La disposition tablette évite les parcours répétés de la page entière. La fréquence élevée et l’échantillonnage du thème réutilisent les entrées identiques tout en conservant les invalidations liées au focus, à l’écran, aux réglages et à la génération de page.
- Le démarrage réduit les lectures répétées de l’APK et charge les modules Python facultatifs à la demande. Les limites des permissions, des données et des erreurs sont conservées.
- Les environnements principal et de secours indépendant partagent les mêmes octets d’archive pour conserver la taille de l’APK ; leurs répertoires et cycles de vie restent indépendants.
- Les résultats de l’hôte, du navigateur et de l’appareil sont consignés séparément. Le rapport de livraison précise les résultats ; aucune accélération fixe ni couverture de toutes les formes historiques n’est promise.
