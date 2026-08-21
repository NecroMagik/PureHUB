// IPureHubService.aidl
package com.necromagik.purehub;

interface IPureHubService {
    /**
     * Запуск перехвата кнопок громкости
     */
    void startVolumeListener();

    /**
     * Остановка перехвата кнопок громкости
     */
    void stopVolumeListener();

    /**
     * Проверка, запущен ли слушатель
     */
    boolean isListening();

    /**
     * Остановка самого UserService процесса
     */
    void destroy();
}