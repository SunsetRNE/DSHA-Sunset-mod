# DSHA 0.2.1-rc.5-20261104.1412-dsh0.2.1-rc.5 / build308

- Lors de la confirmation d’une opération sur un appareil externe, la cible sélectionnée est fixée. Les étapes qui n’ont pas encore été envoyées sont refusées si la requête devient invalide, si l’association de l’appareil change ou si l’accès externe est désactivé.
- Les commandes de diagnostic en lecture seule pour les appareils externes vérifient leurs arguments. wm permet uniquement de consulter la taille et la densité de l’écran. logcat accepte les options de lecture et les filtres pris en charge. Les arguments qui modifient l’affichage, effacent les journaux, redimensionnent les tampons ou écrivent dans un fichier sont refusés.
